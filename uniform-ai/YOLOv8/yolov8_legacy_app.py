from __future__ import annotations

import json
import os
import sys
import uuid
from datetime import datetime
from pathlib import Path
from threading import Lock
from typing import Any

import cv2
from flask import Flask, jsonify, render_template, request, url_for
from PIL import Image, UnidentifiedImageError
from ultralytics import YOLO
from werkzeug.datastructures import FileStorage
from werkzeug.utils import secure_filename


BASE_DIR = Path(__file__).resolve().parent
APP_MODULE_DIR = BASE_DIR.parent / "app"
if str(APP_MODULE_DIR) not in sys.path:
    sys.path.insert(0, str(APP_MODULE_DIR))

from uniform_validation.labels import ANNOTATION_COLORS_BGR, vietnamese_component_label
from utils.annotation_utils import draw_unicode_label

UPLOAD_DIR = BASE_DIR / "static" / "uploads"
RESULT_DIR = BASE_DIR / "static" / "results"

ALLOWED_EXTENSIONS = {"jpg", "jpeg", "png", "bmp", "webp"}
DEFAULT_CONFIDENCE = 0.25
DEFAULT_IMAGE_SIZE = 640
EXPECTED_CLASS_NAMES = {
    0: "ao_so_mi_trang",
    1: "ao_doan_thanh_nien",
    2: "quan_tay_dai_den",
    3: "khan_quang_do",
}
BOX_COLORS_BGR = {
    0: ANNOTATION_COLORS_BGR["ao_so_mi_trang"],
    1: ANNOTATION_COLORS_BGR["ao_doan_thanh_nien"],
    2: ANNOTATION_COLORS_BGR["quan_tay_dai_den"],
    3: ANNOTATION_COLORS_BGR["khan_quang_do"],
}

UPLOAD_DIR.mkdir(parents=True, exist_ok=True)
RESULT_DIR.mkdir(parents=True, exist_ok=True)

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 25 * 1024 * 1024


class UploadError(ValueError):
    """Raised when a user upload cannot be processed safely."""


def _load_names_from_json(path: Path) -> dict[int, str]:
    data = json.loads(path.read_text(encoding="utf-8"))
    return {int(class_id): str(name) for class_id, name in data.items()}


def _load_names_from_txt(path: Path) -> dict[int, str]:
    lines = [line.strip() for line in path.read_text(encoding="utf-8").splitlines()]
    return {index: name for index, name in enumerate(lines) if name}


def _load_names_from_data_yaml(path: Path) -> dict[int, str]:
    names: dict[int, str] = {}
    in_names_block = False

    for raw_line in path.read_text(encoding="utf-8").splitlines():
        stripped = raw_line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        if stripped == "names:":
            in_names_block = True
            continue
        if in_names_block and not raw_line.startswith((" ", "\t")):
            break
        if in_names_block and ":" in stripped:
            class_id, class_name = stripped.split(":", 1)
            if class_id.strip().isdigit():
                names[int(class_id.strip())] = class_name.strip().strip("'\"")

    return names


def _verify_class_names(source_name: str, names: dict[int, str]) -> None:
    if names != EXPECTED_CLASS_NAMES:
        raise RuntimeError(
            f"{source_name} does not match the expected 4 uniform classes: {names}"
        )


def load_verified_class_names() -> dict[int, str]:
    loaders = {
        "class_names.json": _load_names_from_json,
        "classes.txt": _load_names_from_txt,
        "data.yaml": _load_names_from_data_yaml,
    }
    loaded_sources: dict[str, dict[int, str]] = {}

    for filename, loader in loaders.items():
        path = BASE_DIR / filename
        if path.exists():
            names = loader(path)
            _verify_class_names(filename, names)
            loaded_sources[filename] = names

    if not loaded_sources:
        raise RuntimeError("No class name metadata file was found.")

    return loaded_sources.get("class_names.json") or next(iter(loaded_sources.values()))


def normalize_model_names(names: Any) -> dict[int, str]:
    if isinstance(names, dict):
        return {int(class_id): str(name) for class_id, name in names.items()}
    return {index: str(name) for index, name in enumerate(names)}


def resolve_weights_path() -> Path:
    best_path = BASE_DIR / "best.pt"
    if best_path.exists():
        return best_path

    last_path = BASE_DIR / "last.pt"
    if last_path.exists():
        return last_path

    raise FileNotFoundError("Could not find best.pt or last.pt in the project folder.")


CLASS_NAMES = load_verified_class_names()
WEIGHTS_PATH = resolve_weights_path()
MODEL = YOLO(str(WEIGHTS_PATH))
_verify_class_names("model.names", normalize_model_names(MODEL.names))
MODEL_LOCK = Lock()


def is_allowed_file(filename: str) -> bool:
    return "." in filename and filename.rsplit(".", 1)[1].lower() in ALLOWED_EXTENSIONS


def parse_confidence(value: str | None) -> float:
    if value in (None, ""):
        return DEFAULT_CONFIDENCE
    try:
        confidence = float(value)
    except ValueError as exc:
        raise UploadError("Ngưỡng tin cậy không hợp lệ.") from exc
    if not 0.0 <= confidence <= 1.0:
        raise UploadError("Ngưỡng tin cậy phải nằm trong khoảng 0.0 đến 1.0.")
    return confidence


def parse_image_size(value: str | None) -> int:
    if value in (None, ""):
        return DEFAULT_IMAGE_SIZE
    try:
        image_size = int(value)
    except ValueError as exc:
        raise UploadError("Kích thước ảnh không hợp lệ.") from exc
    if not 320 <= image_size <= 1280:
        raise UploadError("Kích thước ảnh phải nằm trong khoảng 320 đến 1280.")
    return image_size


def make_unique_filename(original_filename: str) -> str:
    safe_name = secure_filename(original_filename)
    suffix = Path(safe_name).suffix.lower()
    stem = Path(safe_name).stem[:50] or "upload"
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    return f"{timestamp}_{uuid.uuid4().hex[:8]}_{stem}{suffix}"


def validate_saved_image(path: Path) -> None:
    try:
        with Image.open(path) as image:
            image.verify()
    except (UnidentifiedImageError, OSError) as exc:
        raise UploadError("Tệp tải lên không phải là ảnh hợp lệ.") from exc


def save_uploaded_file(file: FileStorage) -> Path:
    if not file or not file.filename:
        raise UploadError("Vui lòng chọn một tệp ảnh.")
    if not is_allowed_file(file.filename):
        raise UploadError("Định dạng ảnh không hợp lệ. Chỉ hỗ trợ jpg, jpeg, png, bmp, webp.")

    upload_path = UPLOAD_DIR / make_unique_filename(file.filename)
    file.save(upload_path)

    try:
        validate_saved_image(upload_path)
    except UploadError:
        upload_path.unlink(missing_ok=True)
        raise

    return upload_path


def extract_detections(result: Any) -> list[dict[str, Any]]:
    detections: list[dict[str, Any]] = []
    if result.boxes is None or len(result.boxes) == 0:
        return detections

    for index, box in enumerate(result.boxes, start=1):
        class_id = int(box.cls.detach().cpu().item())
        confidence = float(box.conf.detach().cpu().item())
        xyxy = [float(value) for value in box.xyxy[0].detach().cpu().tolist()]

        if class_id not in CLASS_NAMES:
            raise RuntimeError(f"Model returned an unknown class id: {class_id}")

        detections.append(
            {
                "index": index,
                "class_id": class_id,
                "class_name": CLASS_NAMES[class_id],
                "confidence": round(confidence, 4),
                "bbox_xyxy": [round(value, 2) for value in xyxy],
                "x1": round(xyxy[0], 2),
                "y1": round(xyxy[1], 2),
                "x2": round(xyxy[2], 2),
                "y2": round(xyxy[3], 2),
            }
        )

    return detections


def save_annotated_image(upload_path: Path, result_path: Path, detections: list[dict[str, Any]]) -> None:
    image = cv2.imread(str(upload_path))
    if image is None:
        raise RuntimeError("Could not read the uploaded image for annotation.")

    image_height, image_width = image.shape[:2]
    line_thickness = max(2, round(min(image_height, image_width) / 320))
    font_size = max(16, min(30, int(round(min(image_height, image_width) / 32))))
    used_label_rects: list[tuple[int, int, int, int]] = []

    for detection in detections:
        class_id = detection["class_id"]
        x1, y1, x2, y2 = [int(round(value)) for value in detection["bbox_xyxy"]]
        color = BOX_COLORS_BGR.get(class_id, (255, 255, 255))
        label = vietnamese_component_label(detection["class_name"])

        cv2.rectangle(image, (x1, y1), (x2, y2), color, thickness=line_thickness)
        draw_unicode_label(image, label, (x1, y1, x2, y2), color, font_size, used_label_rects)

    if not cv2.imwrite(str(result_path), image):
        raise RuntimeError("Could not save the annotated image.")


def predict_image(upload_path: Path, confidence: float, image_size: int) -> dict[str, Any]:
    with MODEL_LOCK:
        results = MODEL.predict(
            source=str(upload_path),
            conf=confidence,
            imgsz=image_size,
            verbose=False,
        )

    result = results[0]
    detections = extract_detections(result)
    result_filename = f"{upload_path.stem}_result.jpg"
    result_path = RESULT_DIR / result_filename
    save_annotated_image(upload_path, result_path, detections)

    return {
        "detections": detections,
        "original_image_url": url_for("static", filename=f"uploads/{upload_path.name}"),
        "annotated_image_url": url_for("static", filename=f"results/{result_filename}"),
        "uploaded_filename": upload_path.name,
        "result_filename": result_filename,
    }


def handle_prediction_request(file: FileStorage, confidence: float, image_size: int) -> dict[str, Any]:
    upload_path = save_uploaded_file(file)
    return predict_image(upload_path, confidence, image_size)


@app.route("/", methods=["GET", "POST"])
def index():
    context: dict[str, Any] = {
        "class_names": CLASS_NAMES,
        "default_confidence": DEFAULT_CONFIDENCE,
        "default_image_size": DEFAULT_IMAGE_SIZE,
        "selected_confidence": DEFAULT_CONFIDENCE,
        "selected_image_size": DEFAULT_IMAGE_SIZE,
        "weights_name": WEIGHTS_PATH.name,
    }

    if request.method == "POST":
        context["selected_confidence"] = request.form.get("confidence", DEFAULT_CONFIDENCE)
        context["selected_image_size"] = request.form.get("image_size", DEFAULT_IMAGE_SIZE)
        try:
            confidence = parse_confidence(request.form.get("confidence"))
            image_size = parse_image_size(request.form.get("image_size"))
            file = request.files.get("image")
            prediction = handle_prediction_request(file, confidence, image_size)
            context.update(prediction)
            context["selected_confidence"] = confidence
            context["selected_image_size"] = image_size
        except UploadError as exc:
            context["error"] = str(exc)
        except Exception:
            app.logger.exception("Prediction failed")
            context["error"] = "Có lỗi xảy ra khi xử lý ảnh. Vui lòng kiểm tra lại mô hình và tệp ảnh."

    return render_template("index.html", **context)


@app.route("/api/predict", methods=["POST"])
def api_predict():
    file = request.files.get("image") or request.files.get("file")
    try:
        confidence = parse_confidence(request.form.get("confidence") or request.args.get("confidence"))
        image_size = parse_image_size(request.form.get("image_size") or request.args.get("image_size"))
        prediction = handle_prediction_request(file, confidence, image_size)
    except UploadError as exc:
        return jsonify({"success": False, "error": str(exc)}), 400
    except Exception:
        app.logger.exception("API prediction failed")
        return jsonify({"success": False, "error": "Có lỗi xảy ra khi xử lý ảnh."}), 500

    return jsonify(
        {
            "success": True,
            "detections": [
                {
                    "class_id": detection["class_id"],
                    "class_name": detection["class_name"],
                    "confidence": detection["confidence"],
                    "bbox_xyxy": detection["bbox_xyxy"],
                }
                for detection in prediction["detections"]
            ],
            "annotated_image_url": prediction["annotated_image_url"],
            "original_image_url": prediction["original_image_url"],
        }
    )


if __name__ == "__main__":
    port = int(os.environ.get("UNIFORM_APP_PORT", "5001"))
    app.run(host="127.0.0.1", port=port, debug=False)
