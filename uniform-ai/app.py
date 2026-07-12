import json
import sys
import logging
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from flask import Flask, request, send_from_directory, url_for
from flask_cors import CORS
from werkzeug.exceptions import RequestEntityTooLarge

BASE_DIR = Path(__file__).resolve().parent
APP_MODULE_DIR = BASE_DIR / "app"
if str(APP_MODULE_DIR) not in sys.path:
    sys.path.insert(0, str(APP_MODULE_DIR))

from config import get_config
from face.config import ensure_face_storage
from face.face_engine import FaceEngine
from face.response_utils import ApiError as FaceApiError
from face.response_utils import error_response as face_error_response
from face.response_utils import success_response as ai_success_response
from face.routes import (
    face_bp,
    face_health_data,
    identify_image,
    identify_image_for_selected_pose,
    verify_image,
    verify_image_for_selected_pose,
)
from face.student_repository import StudentRepository
from pose_estimation import PoseEstimationError, PoseEstimator, build_pose_regions
from pose_estimation.visualization import save_pose_validation_visualization
from realtime_camera import analyze_realtime_camera_request
from services.evaluation_repository import UniformEvaluationRepository
from services.florence_service import FlorenceService
from services.grounding_service import GroundingService
from services.parsing_service import ParsingService
from services.rule_engine import RuleEngine
from services.yolov8_service import YoloV8Service
from uniform_validation import required_items_from_detections, validate_yolo_detections_for_pose
from uniform_validation.labels import (
    CANONICAL_CLASS_NAME_TO_ID,
    LEGACY_METHOD_GROUNDING_DINO,
    LEGACY_METHOD_YOLOV8,
    METHOD_FILENAME_PREFIXES,
    METHOD_GROUNDING_DINO,
    METHOD_LIGHTWEIGHT_GROUNDING_DINO,
    METHOD_LIGHTWEIGHT_YOLOV8,
    METHOD_YOLOV8,
    REJECTED_OUTSIDE_BODY_LABEL,
    REQUIRED_COMPONENT_KEYS,
    VIETNAMESE_COMPONENT_LABELS,
    required_component_labels,
    vietnamese_component_label,
)
from utils.file_utils import (
    ensure_runtime_directories,
    is_allowed_image_file,
    load_rgb_image,
)
from utils.image_storage import store_pre_ai_upload
from utils.response_utils import error_response, json_response

logger = logging.getLogger(__name__)

config = get_config()
face_storage_migration = ensure_face_storage(config)
ensure_runtime_directories(config)

app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = max(config.MAX_CONTENT_LENGTH_MB, config.FACE_MAX_CONTENT_LENGTH_MB) * 1024 * 1024
app.config.update(
    FACE_STORAGE_DIR=config.FACE_STORAGE_DIR,
    FACE_UPLOAD_DIR=config.FACE_UPLOAD_DIR,
    FACE_EMBEDDING_DIR=config.FACE_EMBEDDING_DIR,
    FACE_METADATA_FILE=config.FACE_METADATA_FILE,
    FACE_ALLOWED_IMAGE_EXTENSIONS=config.FACE_ALLOWED_IMAGE_EXTENSIONS,
    FACE_SIMILARITY_THRESHOLD=config.FACE_SIMILARITY_THRESHOLD,
    INSIGHTFACE_MODEL_NAME=config.INSIGHTFACE_MODEL_NAME,
    INSIGHTFACE_MODEL_ROOT=config.INSIGHTFACE_MODEL_ROOT,
    INSIGHTFACE_CTX_ID=config.INSIGHTFACE_CTX_ID,
    INSIGHTFACE_PROVIDERS=config.INSIGHTFACE_PROVIDERS,
    INSIGHTFACE_DET_SIZE=config.INSIGHTFACE_DET_SIZE,
)
CORS(app, resources={r"/api/*": {"origins": config.CORS_ORIGINS}, r"/static/*": {"origins": config.CORS_ORIGINS}})

grounding_service = GroundingService(config)
parsing_service = ParsingService(config)
florence_service = FlorenceService(config)
rule_engine = RuleEngine(config)
yolov8_service = YoloV8Service(config)
pose_estimator = PoseEstimator(config)
uniform_evaluation_repository = UniformEvaluationRepository(
    evaluations_dir=config.UNIFORM_EVALUATION_RECORD_DIR,
    selections_dir=config.UNIFORM_SELECTION_RECORD_DIR,
)
app.extensions["face_engine"] = FaceEngine(
    model_name=config.INSIGHTFACE_MODEL_NAME,
    providers=config.INSIGHTFACE_PROVIDERS,
    ctx_id=config.INSIGHTFACE_CTX_ID,
    det_size=config.INSIGHTFACE_DET_SIZE,
    model_root=config.INSIGHTFACE_MODEL_ROOT,
)
app.extensions["student_repository"] = StudentRepository(
    embedding_dir=config.FACE_EMBEDDING_DIR,
    metadata_file=config.FACE_METADATA_FILE,
)
app.extensions["pose_estimator"] = pose_estimator
app.extensions["face_storage_migration"] = face_storage_migration
app.register_blueprint(face_bp, url_prefix="/api/face")

REQUIRED_ITEM_KEYS = REQUIRED_COMPONENT_KEYS
GROUNDING_METHOD_KEYS = {METHOD_GROUNDING_DINO, METHOD_LIGHTWEIGHT_GROUNDING_DINO}
YOLO_METHOD_KEYS = {METHOD_YOLOV8, METHOD_LIGHTWEIGHT_YOLOV8}
UNIFORM_METHODS = {
    "hybrid",
    "method_1",
    "method_2",
    "grounding_dino",
    "yolov8",
    "grounding_dino_v2",
    "yolov8_v2",
    "grounding_dino_schp_florence2",
    "yolov8_schp_florence2",
}


def is_grounding_method(method: str) -> bool:
    return method in GROUNDING_METHOD_KEYS


def is_yolov8_method(method: str) -> bool:
    return method in YOLO_METHOD_KEYS


def normalize_lightweight_method(value: str | None) -> str:
    raw = str(value or "").strip()
    if not raw:
        raise ValueError(
            "uniform_method is required and must be one of: "
            "LIGHTWEIGHT_GROUNDING_DINO, LIGHTWEIGHT_YOLOV8_UNIFORM, "
            "GROUNDING_DINO_V2, YOLOV8_V2."
        )
    normalized = raw.lower()
    grounding_aliases = {
        METHOD_LIGHTWEIGHT_GROUNDING_DINO.lower(),
        METHOD_GROUNDING_DINO.lower(),
        "method_1",
        "lightweight_method_1",
        "grounding_dino",
        "grounding_dino_v2",
        "lightweight_grounding_dino",
        "pose_insightface_grounding_dino",
        "no_schp_grounding_dino",
        "no_florence_grounding_dino",
    }
    yolo_aliases = {
        METHOD_LIGHTWEIGHT_YOLOV8.lower(),
        METHOD_YOLOV8.lower(),
        "method_2",
        "lightweight_method_2",
        "yolov8",
        "yolov8_v2",
        "lightweight_yolov8",
        "lightweight_yolov8_uniform",
        "pose_insightface_yolov8",
        "pose_insightface_yolov8_uniform",
        "no_schp_yolov8",
        "no_florence_yolov8",
    }
    if normalized in grounding_aliases:
        return METHOD_LIGHTWEIGHT_GROUNDING_DINO
    if normalized in yolo_aliases:
        return METHOD_LIGHTWEIGHT_YOLOV8
    raise ValueError(
        "uniform_method must be one of: LIGHTWEIGHT_GROUNDING_DINO, "
        "LIGHTWEIGHT_YOLOV8_UNIFORM, GROUNDING_DINO_V2, YOLOV8_V2."
    )


def lightweight_method_from_request(default: str | None = None) -> str:
    raw = (
        request.form.get("uniform_method")
        or request.form.get("selected_method")
        or request.form.get("method")
        or request.args.get("uniform_method")
        or request.args.get("selected_method")
        or request.args.get("method")
        or default
    )
    if raw is None or not str(raw).strip():
        raise ValueError(
            "uniform_method is required and must be one of: "
            "LIGHTWEIGHT_GROUNDING_DINO, LIGHTWEIGHT_YOLOV8_UNIFORM, "
            "GROUNDING_DINO_V2, YOLOV8_V2."
        )
    return normalize_lightweight_method(raw)


@app.errorhandler(FaceApiError)
def handle_face_api_error(error: FaceApiError):
    return face_error_response(
        error.message,
        status_code=error.status_code,
        code=error.code,
        details=error.details,
    )


@app.errorhandler(RequestEntityTooLarge)
def handle_payload_too_large(_error):
    return face_error_response(
        "Uploaded file is too large.",
        status_code=413,
        code="PAYLOAD_TOO_LARGE",
        details={"max_content_length_mb": app.config["MAX_CONTENT_LENGTH"] // (1024 * 1024)},
    )


def default_required_items() -> dict:
    return {key: {"present": False, "score": 0.0} for key in REQUIRED_ITEM_KEYS}


def parse_bool(value, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    if str(value).strip() == "":
        return default
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def parse_uniform_method(value) -> str:
    method = (value or "hybrid").strip().lower()
    if method not in UNIFORM_METHODS:
        raise ValueError("uniform_method must be one of: hybrid, method_1, method_2, grounding_dino_v2, yolov8_v2.")
    if method in {"grounding_dino", "grounding_dino_v2", "grounding_dino_schp_florence2"}:
        return "method_1"
    if method in {"yolov8", "yolov8_v2", "yolov8_schp_florence2"}:
        return "method_2"
    return method


def uniform_health_data() -> dict:
    return {
        "status": "ok",
        "service": "uniform-ai",
        "port": config.PORT,
        "device": config.DEVICE,
        "yolov8": yolov8_service.status(),
        "grounding_dino": grounding_service.status(),
        "schp": parsing_service.status(),
        "florence2": florence_service.status(),
        "pose": pose_estimator.status(),
        "outputs_dir": str(config.YOLO_OUTPUT_DIR),
        "pre_ai_image_dir": str(config.UNIFORM_PRE_AI_IMAGE_DIR),
    }


def validate_image_upload():
    if "image" not in request.files:
        return None, error_response("Missing image file. Use multipart key 'image'.", status_code=400)

    upload = request.files["image"]
    if upload is None or not upload.filename:
        return None, error_response("Empty image file.", status_code=400)

    if not is_allowed_image_file(upload.filename, config.ALLOWED_IMAGE_EXTENSIONS):
        ext_hint = ", ".join(sorted(config.ALLOWED_IMAGE_EXTENSIONS))
        return None, error_response(
            f"Unsupported image format. Allowed extensions: {ext_hint}.",
            status_code=400,
        )

    return upload, None


def yolov8_output_url(filename: str | None) -> str | None:
    if not filename:
        return None
    safe_name = Path(str(filename).replace("\\", "/")).name
    output_path = config.YOLO_OUTPUT_DIR / safe_name
    if not output_path.exists() or not output_path.is_file():
        logger.warning("processed_image_missing path=%s", output_path)
        return None
    return url_for("serve_yolov8_output", filename=safe_name)


def output_url_for_path(image_path: str | Path | None) -> str | None:
    if not image_path:
        return None
    path = Path(image_path)
    if not path.exists() or not path.is_file():
        logger.warning("processed_image_missing path=%s", path)
        return None
    try:
        resolved = path.resolve()
        yolo_dir = config.YOLO_OUTPUT_DIR.resolve()
        output_dir = config.OUTPUT_DIR.resolve()
        if resolved.is_relative_to(yolo_dir):
            return yolov8_output_url(resolved.name)
        if resolved.is_relative_to(output_dir):
            return url_for("serve_uniform_output", filename=resolved.relative_to(output_dir).as_posix())
    except Exception:
        pass
    return None


def image_shape_from_pil(image) -> tuple[int, int, int]:
    return int(image.height), int(image.width), 3


def crop_image_to_bbox(image, bbox: list[float] | None, padding_ratio: float = 0.04):
    if not bbox:
        return image
    x1, y1, x2, y2 = [float(value) for value in bbox[:4]]
    if x2 <= x1 or y2 <= y1:
        return image
    width, height = image.size
    box_w = x2 - x1
    box_h = y2 - y1
    pad_x = box_w * padding_ratio
    pad_y = box_h * padding_ratio
    cx1 = max(0, int(round(x1 - pad_x)))
    cy1 = max(0, int(round(y1 - pad_y)))
    cx2 = min(width, int(round(x2 + pad_x)))
    cy2 = min(height, int(round(y2 + pad_y)))
    if cx2 - cx1 < 16 or cy2 - cy1 < 16:
        return image
    return image.crop((cx1, cy1, cx2, cy2))


def public_pose_payload(pose_result: dict | None) -> dict:
    if not pose_result:
        return {
            "available": False,
            "selected": False,
            "selected_person": None,
            "person_count": 0,
            "reason": "Pose estimation did not run",
        }
    return {
        "available": bool(pose_result.get("available", False)),
        "model": pose_result.get("model"),
        "model_path": pose_result.get("model_path"),
        "method": pose_result.get("method", "largest_pose_area"),
        "person_count": int(pose_result.get("person_count", 0)),
        "selected": bool(pose_result.get("selected", False)),
        "selected_person_index": pose_result.get("selected_person_index"),
        "selected_person": pose_result.get("selected_person"),
        "people": pose_result.get("people", []),
        "reason": pose_result.get("reason"),
        "thresholds": pose_result.get("thresholds", {}),
    }


def public_yolov8_payload(prediction: dict) -> dict:
    return {
        "available": True,
        "model": prediction.get("model", "yolov8_v2"),
        "model_id": prediction.get("model_id"),
        "model_version": prediction.get("model_version"),
        "source_detector": prediction.get("source_detector"),
        "weights": prediction.get("weights"),
        "weights_path": prediction.get("weights_path"),
        "confidence_threshold": prediction.get("confidence_threshold"),
        "image_size": prediction.get("image_size"),
        "max_det": prediction.get("max_det"),
        "class_mapping": prediction.get("class_mapping"),
        "model_metadata": prediction.get("model_metadata"),
        "detections": prediction.get("detections", []),
        "raw_yolo_detections": prediction.get("raw_yolo_detections", prediction.get("detections", [])),
        "summary": prediction.get("summary", {"total_detections": 0, "detected_classes": []}),
        "annotated_image_url": yolov8_output_url(prediction.get("annotated_filename")),
    }


def estimate_pose_for_image(saved_path: Path, image_shape: tuple[int, int, int]) -> tuple[dict, list[str]]:
    notes: list[str] = []
    try:
        pose_result = pose_estimator.estimate(saved_path)
        selected_person = pose_result.get("selected_person")
        if selected_person:
            notes.append("Selected closest person using largest pose area.")
        else:
            notes.append(pose_result.get("reason") or "No selected closest person pose is available.")
        return pose_result, notes
    except PoseEstimationError as exc:
        return (
            {
                "available": False,
                "model": "yolov8-pose",
                "model_path": config.POSE_MODEL_PATH,
                "method": "largest_pose_area",
                "image_shape": list(image_shape),
                "people": [],
                "person_count": 0,
                "selected_person": None,
                "selected_person_index": None,
                "selected": False,
                "reason": str(exc),
            },
            [f"Pose estimation failed: {exc}"],
        )


def run_pose_validated_yolov8(
    saved_path: Path,
    image_shape: tuple[int, int, int],
    yolo_confidence=None,
    yolo_image_size=None,
    yolo_save_annotated=None,
    pose_result: dict | None = None,
    filename_prefix: str | None = None,
) -> tuple[dict, dict, dict, list[str]]:
    notes: list[str] = []
    if pose_result is None:
        pose_result, pose_notes = estimate_pose_for_image(saved_path, image_shape)
        notes.extend(pose_notes)

    yolo_output = yolov8_service.predict(
        saved_path,
        confidence=yolo_confidence,
        image_size=yolo_image_size,
        save_annotated=False,
    )

    validation_payload = validate_yolo_detections_for_pose(
        yolo_output.get("detections", []),
        pose_result.get("selected_person"),
        image_shape,
        config,
    )
    validation_payload["raw_yolo_detections"] = validation_payload.get("raw_yolo_detections", yolo_output.get("detections", []))
    validation_payload["source_detector"] = yolo_output.get("source_detector", "yolov8_v2")
    validation_payload["detector_model_id"] = yolo_output.get("model_id")
    validation_payload["detector_model_version"] = yolo_output.get("model_version")
    validation_payload["detector_confidence_threshold"] = yolo_output.get("confidence_threshold")

    if validation_payload["summary"].get("reason"):
        notes.append(validation_payload["summary"]["reason"])

    annotated_path = save_pose_validation_visualization(
        image_path=saved_path,
        selected_person=pose_result.get("selected_person"),
        accepted_detections=validation_payload["accepted_detections"],
        rejected_detections=validation_payload["rejected_detections"],
        output_dir=config.YOLO_OUTPUT_DIR,
        min_keypoint_confidence=config.POSE_MIN_CONFIDENCE,
        show_rejected=config.SHOW_REJECTED_UNIFORM_DETECTIONS,
        filename_prefix=filename_prefix,
    )
    final_image_url = yolov8_output_url(annotated_path.name)
    yolo_output["annotated_path"] = str(annotated_path)
    yolo_output["annotated_filename"] = annotated_path.name
    yolo_output["final_annotated_path"] = str(annotated_path)
    yolo_output["final_annotated_filename"] = annotated_path.name
    validation_payload["annotated_image_url"] = final_image_url
    validation_payload["final_annotated_image_url"] = final_image_url
    validation_payload["processed_image_url"] = final_image_url
    validation_payload["final_annotated_image_path"] = str(annotated_path)
    validation_payload["visualization"] = {
        "type": "combined_pose_yolov8",
        "contains_selected_pose_skeleton": pose_result.get("selected_person") is not None,
        "contains_accepted_yolo_boxes": bool(validation_payload["accepted_detections"]),
        "contains_rejected_yolo_boxes": bool(
            config.SHOW_REJECTED_UNIFORM_DETECTIONS and validation_payload["rejected_detections"]
        ),
        "shows_rejected_yolo_boxes_when_present": bool(config.SHOW_REJECTED_UNIFORM_DETECTIONS),
        "image_url": final_image_url,
        "image_path": str(annotated_path),
    }

    return yolo_output, pose_result, validation_payload, notes


def merge_required_items_with_yolov8(required_items: dict, yolov8_detections: list[dict]) -> tuple[dict, list[str]]:
    merged = default_required_items()
    notes: list[str] = []

    for key in REQUIRED_ITEM_KEYS:
        item = required_items.get(key, {})
        score = round(float(item.get("score", 0.0)), 3)
        merged[key] = {
            "present": bool(item.get("present", False)),
            "score": score,
        }

    for detection in yolov8_detections:
        class_name = detection.get("class_name")
        if class_name not in merged:
            continue
        score = round(float(detection.get("confidence", 0.0)), 3)
        previous_score = float(merged[class_name]["score"])
        if score > previous_score:
            if previous_score > 0:
                notes.append(f"YOLOv8 strengthened {class_name}: {previous_score:.3f} -> {score:.3f}.")
            else:
                notes.append(f"YOLOv8 detected {class_name} with confidence {score:.3f}.")
            merged[class_name] = {
                "present": score >= config.REQUIRED_ITEM_PRESENT_THRESHOLD,
                "score": score,
            }

    for key in REQUIRED_ITEM_KEYS:
        merged[key]["present"] = bool(merged[key]["score"] >= config.REQUIRED_ITEM_PRESENT_THRESHOLD)

    return merged, notes


def grounding_detections_to_uniform_detections(grounding_output: dict) -> list[dict]:
    normalized = grounding_output.get("uniform_detections")
    if isinstance(normalized, list):
        return [dict(detection) for detection in normalized]

    detections: list[dict] = []
    for index, detection in enumerate(grounding_output.get("detections", []), start=1):
        label = str(detection.get("label", "")).lower().strip()
        score = round(float(detection.get("score", 0.0)), 4)
        bbox = [round(float(value), 2) for value in detection.get("box", [])[:4]]
        if len(bbox) != 4:
            continue
        class_name = grounding_service.canonical_label_for_text(label)
        if class_name is None:
            continue
        x1, y1, x2, y2 = bbox
        detections.append(
            {
                "detection_id": f"{grounding_service.SOURCE_DETECTOR}_{index:04d}",
                "index": index,
                "selection_rank": index,
                "class_id": CANONICAL_CLASS_NAME_TO_ID[class_name],
                "class_name": class_name,
                "source_label": label,
                "confidence": score,
                "bbox": bbox,
                "bbox_xyxy": bbox,
                "xyxy": bbox,
                "bbox_area": round(float(max(0.0, x2 - x1) * max(0.0, y2 - y1)), 2),
                "x1": bbox[0],
                "y1": bbox[1],
                "x2": bbox[2],
                "y2": bbox[3],
                "source_detector": grounding_service.SOURCE_DETECTOR,
                "model_version": grounding_service.model_id,
                "pose_validation": None,
                "accepted": None,
                "rejection_reason": None,
            }
        )
    return detections


def utc_now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def pre_ai_image_url(image_path: str | Path | None) -> str | None:
    if not image_path:
        return None
    path = Path(image_path)
    return url_for("serve_pre_ai_image", filename=path.name)


def _bbox_from_public_detection(detection: dict) -> list[float]:
    bbox = detection.get("bbox") or detection.get("bbox_xyxy")
    if bbox is None:
        bbox = [detection.get("x1", 0), detection.get("y1", 0), detection.get("x2", 0), detection.get("y2", 0)]
    return [round(float(value), 2) for value in bbox[:4]]


def _accepted_components(detections: list[dict]) -> list[dict]:
    accepted: list[dict] = []
    for detection in detections:
        class_name = str(detection.get("class_name", ""))
        if class_name not in VIETNAMESE_COMPONENT_LABELS:
            continue
        accepted.append(
            {
                "label": vietnamese_component_label(class_name),
                "class_name": class_name,
                "bbox": _bbox_from_public_detection(detection),
                "confidence": round(float(detection.get("confidence", 0.0)), 4),
                "source_label": detection.get("source_label"),
                "pose_overlap_ratio": detection.get("pose_overlap_ratio"),
                "body_overlap_ratio": detection.get("body_overlap_ratio"),
            }
        )
    return accepted


def _rejected_components(detections: list[dict]) -> list[dict]:
    rejected: list[dict] = []
    for detection in detections:
        rejected.append(
            {
                "reason": REJECTED_OUTSIDE_BODY_LABEL,
                "bbox": _bbox_from_public_detection(detection),
                "confidence": round(float(detection.get("confidence", 0.0)), 4),
                "source_class_name": detection.get("class_name"),
                "source_label": detection.get("source_label"),
                "pose_overlap_ratio": detection.get("pose_overlap_ratio"),
                "body_overlap_ratio": detection.get("body_overlap_ratio"),
            }
        )
    return rejected


def _duplicate_components(detections: list[dict]) -> list[dict]:
    duplicates: list[dict] = []
    for detection in detections:
        class_name = str(detection.get("class_name", ""))
        duplicates.append(
            {
                "reason": detection.get("rejection_reason") or "duplicate_same_class_lower_confidence",
                "message": "Đã loại bỏ vật thể trùng lớp có độ tin cậy thấp hơn.",
                "label": vietnamese_component_label(class_name),
                "class_name": class_name,
                "bbox": _bbox_from_public_detection(detection),
                "confidence": round(float(detection.get("confidence", 0.0)), 4),
                "source_label": detection.get("source_label"),
                "duplicate_of_detection_id": detection.get("duplicate_of_detection_id"),
            }
        )
    return duplicates


def _tuck_in_assessment(payload: dict) -> dict:
    schp = payload.get("schp", {})
    item = payload.get("appearance", {}).get("tucked_in", {})

    if schp.get("skipped"):
        return {
            "available": False,
            "skipped": True,
            "tucked_in": True,
            "status": "bỏ qua",
            "confidence": 0.0,
            "explanation": schp.get("reason")
            or "Luồng đánh giá nhanh chỉ kiểm tra thành phần đồng phục và bỏ qua SCHP.",
        }

    label = item.get("label", "uncertain")
    confidence = round(float(item.get("score", 0.0)), 3)

    if label == "pass":
        status = "đã sơ vin"
        tucked_in = True
        explanation = "SCHP xác định mép áo nằm gần hoặc phía trên vùng thắt lưng."
    elif label == "fail":
        status = "chưa sơ vin"
        tucked_in = False
        explanation = "SCHP phát hiện phần áo có khả năng kéo xuống dưới vùng thắt lưng."
    else:
        status = "không xác định"
        tucked_in = None
        explanation = "Không xác định được tình trạng sơ vin do ảnh chưa đủ rõ."

    note_translations = {
        "Upper-clothing boundary remains near or above waistband.": "Mép áo nằm gần hoặc phía trên vùng thắt lưng.",
        "Upper clothing likely extends below waistband.": "Phần áo có khả năng kéo xuống dưới vùng thắt lưng.",
        "Tucked-in signal is borderline.": "Tín hiệu sơ vin chưa rõ ràng.",
        "Insufficient clothing pixels for tucked-in estimation.": "Không đủ vùng áo/quần để ước lượng tình trạng sơ vin.",
    }
    notes = schp.get("notes") or []
    if notes:
        explanation = note_translations.get(str(notes[0]), str(notes[0]))
    if not schp.get("available", False):
        explanation = f"SCHP không khả dụng hoặc không chạy được: {schp.get('error') or explanation}"

    return {
        "available": bool(schp.get("available", False)),
        "tucked_in": tucked_in,
        "status": status,
        "confidence": confidence,
        "explanation": explanation,
    }


def _condition_bool(item: dict) -> bool | None:
    label = item.get("label", "uncertain")
    if label == "fail":
        return True
    if label == "pass":
        return False
    return None


def _appearance_assessment(payload: dict) -> dict:
    florence = payload.get("florence2", {})
    appearance = payload.get("appearance", {})

    if florence.get("skipped"):
        return {
            "available": False,
            "skipped": True,
            "wrinkled": False,
            "dirty": False,
            "torn": False,
            "description": florence.get("reason")
            or "Luồng đánh giá nhanh bỏ qua Florence-2 và không kết luận nhăn/bẩn/rách.",
            "model_description": None,
            "confidence": {
                "wrinkled": 0.0,
                "dirty": 0.0,
                "torn": 0.0,
            },
        }

    wrinkled = _condition_bool(appearance.get("wrinkled", {}))
    dirty = _condition_bool(appearance.get("dirty", {}))
    torn = _condition_bool(appearance.get("torn_or_damaged", {}))

    flags = []
    if wrinkled is True:
        flags.append("nhăn")
    if dirty is True:
        flags.append("bẩn")
    if torn is True:
        flags.append("rách")

    if not florence.get("available", False):
        description = "Không xác định được tình trạng nhăn/bẩn/rách vì Florence-2 không khả dụng."
    elif flags:
        description = f"Trang phục có dấu hiệu {'/'.join(flags)}, cần kiểm tra lại thủ công."
    elif wrinkled is False and dirty is False and torn is False:
        description = "Đồng phục nhìn bình thường."
    else:
        description = "Không đủ bằng chứng để kết luận rõ tình trạng nhăn/bẩn/rách."

    return {
        "available": bool(florence.get("available", False)),
        "wrinkled": wrinkled,
        "dirty": dirty,
        "torn": torn,
        "description": description,
        "model_description": florence.get("description"),
        "confidence": {
            "wrinkled": round(float(appearance.get("wrinkled", {}).get("score", 0.0)), 3),
            "dirty": round(float(appearance.get("dirty", {}).get("score", 0.0)), 3),
            "torn": round(float(appearance.get("torn_or_damaged", {}).get("score", 0.0)), 3),
        },
    }


def _final_comment(
    missing_components: list[str],
    rejected_components: list[dict],
    tuck_in: dict,
    appearance: dict,
) -> str:
    comments: list[str] = []
    if missing_components:
        comments.append(f"Thiếu {', '.join(missing_components)}.")
    else:
        comments.append("Học sinh mặc đủ các thành phần đồng phục được yêu cầu.")

    if rejected_components:
        comments.append("Phát hiện thành phần nằm ngoài cơ thể học sinh nên không được tính.")

    if tuck_in.get("status") == "chưa sơ vin":
        comments.append("Áo có dấu hiệu chưa sơ vin.")
    elif tuck_in.get("status") == "không xác định":
        comments.append("Không xác định được tình trạng sơ vin do ảnh chưa đủ rõ.")

    if any(appearance.get(key) is True for key in ["wrinkled", "dirty", "torn"]):
        comments.append("Trang phục có dấu hiệu nhăn/bẩn/rách, cần kiểm tra lại thủ công.")
    elif appearance.get("skipped"):
        comments.append("Luồng nhanh không đánh giá nhăn/bẩn/rách.")
    elif not appearance.get("available"):
        comments.append("Không xác định được tình trạng nhăn/bẩn/rách.")

    return " ".join(comments)


def _structured_candidate_result(method: str, legacy_payload: dict) -> dict:
    validation_key = "pose_validated_grounding_dino" if is_grounding_method(method) else "pose_validated_yolov8"
    validation_payload = legacy_payload.get(validation_key, {})
    accepted = _accepted_components(validation_payload.get("accepted_detections", []))
    rejected = _rejected_components(validation_payload.get("rejected_detections", []))
    duplicate_removed = _duplicate_components(validation_payload.get("removed_duplicate_detections", []))
    accepted_keys = {item["class_name"] for item in accepted}
    missing = [
        VIETNAMESE_COMPONENT_LABELS[key]
        for key in REQUIRED_ITEM_KEYS
        if key not in accepted_keys
    ]
    tuck_in = _tuck_in_assessment(legacy_payload)
    appearance = _appearance_assessment(legacy_payload)
    overall = legacy_payload.get("overall", {})
    compliance = overall.get("compliance")
    is_compliant = True if compliance == "compliant" else False if compliance in {"partially_compliant", "non_compliant"} else None
    score = int(round(float(overall.get("score", 0.0)) * 100))

    return {
        "method": method,
        "required_components": required_component_labels(),
        "accepted_components": accepted,
        "missing_components": missing,
        "rejected_components": rejected,
        "removed_duplicate_components": duplicate_removed,
        "removed_duplicate_detections": validation_payload.get("removed_duplicate_detections", []),
        "detector_trace": validation_payload.get("detector_trace"),
        "tuck_in_assessment": tuck_in,
        "appearance_assessment": appearance,
        "final_summary": {
            "is_compliant": is_compliant,
            "score": max(0, min(100, score)),
            "vietnamese_comment": _final_comment(missing, rejected, tuck_in, appearance),
            "legacy_compliance": compliance,
        },
        "processed_image_path": legacy_payload.get("final_annotated_image_path"),
        "processed_image_url": legacy_payload.get("final_annotated_image_url") or legacy_payload.get("processed_image_url"),
    }


def _write_candidate_sidecar(candidate: dict, sidecar_payload: dict) -> str | None:
    processed_image = candidate.get("processed_image")
    if processed_image:
        sidecar_path = Path(processed_image).with_suffix(".json")
    else:
        prefix = METHOD_FILENAME_PREFIXES.get(candidate["method"], candidate["method"])
        sidecar_path = config.YOLO_OUTPUT_DIR / f"{prefix}_{datetime.utcnow().strftime('%Y%m%d_%H%M%S_%f')}.json"

    sidecar_path.parent.mkdir(parents=True, exist_ok=True)
    with sidecar_path.open("w", encoding="utf-8") as file:
        json.dump(sidecar_payload, file, ensure_ascii=False, indent=2)
        file.write("\n")
    return str(sidecar_path)


def _result_status_label(structured: dict) -> str:
    summary = structured.get("final_summary", {})
    compliant = summary.get("is_compliant")
    if compliant is True:
        return "Đạt"
    if compliant is False:
        return "Chưa đạt"
    return "Cần kiểm tra lại"


def _ensure_candidate_processed_image(
    method: str,
    legacy_payload: dict,
    image_path: Path,
    structured: dict,
) -> tuple[str | None, str | None]:
    processed_path = structured.get("processed_image_path") or legacy_payload.get("final_annotated_image_path")
    processed_url = structured.get("processed_image_url") or legacy_payload.get("final_annotated_image_url")

    if processed_path and Path(processed_path).exists():
        processed_url = processed_url or output_url_for_path(processed_path)
        if processed_url:
            return str(processed_path), processed_url

    prefix = METHOD_FILENAME_PREFIXES.get(method, method)
    try:
        annotated_path = save_pose_validation_visualization(
            image_path=image_path,
            selected_person=(legacy_payload.get("pose", {}).get("selected_person") or None),
            accepted_detections=[],
            rejected_detections=[],
            output_dir=config.YOLO_OUTPUT_DIR,
            min_keypoint_confidence=config.POSE_MIN_CONFIDENCE,
            show_rejected=config.SHOW_REJECTED_UNIFORM_DETECTIONS,
            filename_prefix=prefix,
        )
        processed_path = str(annotated_path)
        processed_url = output_url_for_path(annotated_path)
        legacy_payload["final_annotated_image_path"] = processed_path
        legacy_payload["final_annotated_image_url"] = processed_url
        legacy_payload["processed_image_url"] = processed_url
        structured["processed_image_path"] = processed_path
        structured["processed_image_url"] = processed_url
        logger.info(
            "processed_image_fallback method=%s path=%s size=%s url=%s",
            method,
            processed_path,
            annotated_path.stat().st_size if annotated_path.exists() else 0,
            processed_url,
        )
        return processed_path, processed_url
    except Exception as exc:
        logger.exception("processed_image_fallback_failed method=%s image=%s error=%s", method, image_path, exc)
        return None, None


def _candidate_from_legacy_payload(
    method: str,
    legacy_payload: dict,
    original_image_path: Path,
    pre_ai_info: dict,
    timestamp: str,
) -> dict:
    structured = _structured_candidate_result(method, legacy_payload)
    processed_image, processed_url = _ensure_candidate_processed_image(method, legacy_payload, original_image_path, structured)
    candidate = {
        "method": method,
        "evaluation_method": method,
        "status": "completed",
        "score": structured.get("final_summary", {}).get("score"),
        "result_status": _result_status_label(structured),
        "processed_image": processed_image,
        "processed_image_url": processed_url,
        "annotated_image_url": processed_url,
        "image_url": processed_url,
        "detector_model_id": legacy_payload.get("detector_model_id"),
        "detector_model_version": legacy_payload.get("detector_model_version"),
        "detector_confidence_threshold": legacy_payload.get("detector_confidence_threshold"),
        "raw_detection_count": legacy_payload.get("raw_detection_count"),
        "pose_accepted_detection_count": legacy_payload.get("pose_accepted_detection_count"),
        "final_unique_detection_count": legacy_payload.get("final_unique_detection_count"),
        "duplicate_removed_count": legacy_payload.get("duplicate_removed_count"),
        "detector_trace": structured.get("detector_trace"),
        "result": structured,
        "legacy_result": legacy_payload,
    }
    sidecar_payload = {
        "method": method,
        "timestamp": timestamp,
        "evaluation_method": method,
        "detector_trace": structured.get("detector_trace"),
        "original_image_path": str(original_image_path),
        "preprocessed_stored_input_image_path": pre_ai_info.get("path"),
        "processed_output_image_path": processed_image,
        "selected_student_bounding_box": (
            legacy_payload.get("pose", {}).get("selected_person") or {}
        ).get("pose_bbox"),
        "accepted_uniform_components": structured["accepted_components"],
        "rejected_components": structured["rejected_components"],
        "removed_duplicate_components": structured.get("removed_duplicate_components", []),
        "removed_duplicate_detections": structured.get("removed_duplicate_detections", []),
        "rejection_reasons": [item["reason"] for item in structured["rejected_components"]],
        "SCHP_result": structured["tuck_in_assessment"],
        "Florence_2_result": structured["appearance_assessment"],
        "final_compliance_summary": structured["final_summary"],
        "confidence_values": {
            "accepted_components": [
                {
                    "label": item["label"],
                    "confidence": item["confidence"],
                }
                for item in structured["accepted_components"]
            ],
            "tuck_in": structured["tuck_in_assessment"]["confidence"],
            "appearance": structured["appearance_assessment"]["confidence"],
        },
    }
    sidecar_path = _write_candidate_sidecar(candidate, sidecar_payload)
    candidate["json_sidecar"] = sidecar_path
    candidate["result"]["json_sidecar"] = sidecar_path
    return candidate


def _unavailable_method_candidate(
    method: str,
    image_path: Path,
    pose_result: dict | None,
    pre_ai_info: dict,
    error: str,
    timestamp: str,
) -> dict:
    prefix = METHOD_FILENAME_PREFIXES.get(method, method)
    processed_image_path = None
    processed_image_url = None
    try:
        annotated_path = save_pose_validation_visualization(
            image_path=image_path,
            selected_person=pose_result.get("selected_person") if pose_result else None,
            accepted_detections=[],
            rejected_detections=[],
            output_dir=config.YOLO_OUTPUT_DIR,
            min_keypoint_confidence=config.POSE_MIN_CONFIDENCE,
            show_rejected=config.SHOW_REJECTED_UNIFORM_DETECTIONS,
            filename_prefix=prefix,
        )
        processed_image_path = str(annotated_path)
        processed_image_url = yolov8_output_url(annotated_path.name)
    except Exception as visualization_error:
        error = f"{error}; final visualization failed: {visualization_error}"

    image_shape = tuple(pose_result.get("image_shape", [1, 1, 3])) if pose_result else (1, 1, 3)

    legacy_payload = {
        "required_items": default_required_items(),
        "appearance": {
            "tucked_in": {"label": "uncertain", "score": 0.0},
            "wrinkled": {"label": "uncertain", "score": 0.0},
            "dirty": {"label": "uncertain", "score": 0.0},
            "torn_or_damaged": {"label": "uncertain", "score": 0.0},
        },
        "overall": {"compliance": "needs_review", "score": 0.0},
        "notes": [error],
        "pose": public_pose_payload(pose_result),
        "schp": {"available": False, "error": error, "tucked_in": {"label": "uncertain", "score": 0.0}},
        "florence2": {"available": False, "error": error, "description": None},
        "final_annotated_image_path": processed_image_path,
        "final_annotated_image_url": processed_image_url,
        "processed_image_url": processed_image_url,
        "pose_validated_yolov8": validate_yolo_detections_for_pose([], pose_result.get("selected_person") if pose_result else None, image_shape, config),
        "pose_validated_grounding_dino": validate_yolo_detections_for_pose([], pose_result.get("selected_person") if pose_result else None, image_shape, config),
    }
    return _candidate_from_legacy_payload(method, legacy_payload, image_path, pre_ai_info, timestamp)


def run_uniform_method_candidate(
    method: str,
    image_path: Path,
    pre_ai_info: dict,
    pose_result: dict | None,
    yolo_confidence=None,
    yolo_image_size=None,
    yolo_save_annotated=None,
) -> dict:
    component_method = "method_1" if method == METHOD_GROUNDING_DINO else "method_2"
    prefix = METHOD_FILENAME_PREFIXES.get(method, method)
    timestamp = utc_now_iso()
    try:
        legacy_payload = run_uniform_evaluation(
            image_path,
            yolo_confidence=yolo_confidence,
            yolo_image_size=yolo_image_size,
            yolo_save_annotated=yolo_save_annotated,
            pose_result=pose_result,
            component_method=component_method,
            filename_prefix=prefix,
        )
        return _candidate_from_legacy_payload(method, legacy_payload, image_path, pre_ai_info, timestamp)
    except Exception as exc:
        return _unavailable_method_candidate(method, image_path, pose_result, pre_ai_info, str(exc), timestamp)


def _lightweight_skipped_models() -> dict:
    schp_reason = "SCHP skipped: lightweight no-SCHP/no-Florence uniform component evaluation."
    florence_reason = "Florence-2 skipped: lightweight no-SCHP/no-Florence uniform component evaluation."
    return {
        "schp": {
            "available": False,
            "skipped": True,
            "model": "SCHP ATR",
            "reason": schp_reason,
            "tucked_in": {"label": "pass", "score": 0.0},
            "notes": [schp_reason],
        },
        "florence2": {
            "available": False,
            "skipped": True,
            "model": config.FLORENCE_MODEL_ID,
            "reason": florence_reason,
            "description": None,
            "appearance": {
                "wrinkled": {"label": "pass", "score": 0.0},
                "dirty": {"label": "pass", "score": 0.0},
                "torn_or_damaged": {"label": "pass", "score": 0.0},
            },
            "notes": [florence_reason],
        },
    }


def _lightweight_neutral_appearance() -> dict:
    return {
        "tucked_in": {"label": "pass", "score": 0.0},
        "wrinkled": {"label": "pass", "score": 0.0},
        "dirty": {"label": "pass", "score": 0.0},
        "torn_or_damaged": {"label": "pass", "score": 0.0},
    }


def _lightweight_module_trace(method: str, insightface_executed: bool) -> tuple[list[str], list[str]]:
    detector = "GROUNDING_DINO" if is_grounding_method(method) else "YOLOV8_UNIFORM"
    unselected_detector = "YOLOV8_UNIFORM" if is_grounding_method(method) else "GROUNDING_DINO"
    executed = ["POSE"]
    skipped: list[str] = []
    if insightface_executed:
        executed.append("INSIGHTFACE")
    else:
        skipped.append("INSIGHTFACE")
    executed.append(detector)
    skipped.extend([unselected_detector, "SCHP", "FLORENCE_2"])
    return executed, skipped


def _lightweight_component_payload(
    method: str,
    image_path: Path,
    image_shape: tuple[int, int, int],
    pose_result: dict | None,
    required_items: dict,
    validation_payload: dict,
    grounding_payload: dict,
    yolov8_payload: dict,
    notes: list[str],
    insightface_executed: bool = False,
) -> dict:
    skipped = _lightweight_skipped_models()
    appearance = _lightweight_neutral_appearance()
    payload = rule_engine.aggregate(
        required_items=required_items,
        appearance=appearance,
        notes=notes + [
            "Lightweight flow: SCHP was intentionally skipped.",
            "Lightweight flow: Florence-2 was intentionally skipped.",
        ],
    )
    payload["pose"] = public_pose_payload(pose_result)
    payload["selected_person"] = payload["pose"].get("selected_person")
    payload["component_method"] = "lightweight_method_1" if is_grounding_method(method) else "lightweight_method_2"
    payload["evaluation_method"] = method
    payload["pipeline"] = {
        "name": "no_schp_no_florence_uniform_evaluation",
        "uses_schp": False,
        "uses_florence2": False,
        "uses_pose": True,
        "uses_insightface": bool(insightface_executed),
        "component_detector": "grounding_dino_v2" if is_grounding_method(method) else "yolov8_v2",
    }
    payload["lightweight_no_schp_no_florence"] = True
    payload["skipped_models"] = {
        "schp": skipped["schp"]["reason"],
        "florence2": skipped["florence2"]["reason"],
    }
    payload["schp"] = skipped["schp"]
    payload["florence2"] = skipped["florence2"]
    payload["appearance"] = appearance
    payload["grounding_dino"] = grounding_payload
    payload["yolov8"] = yolov8_payload
    payload["pose_validated_grounding_dino"] = (
        validation_payload
        if is_grounding_method(method)
        else validate_yolo_detections_for_pose([], pose_result.get("selected_person") if pose_result else None, image_shape, config)
    )
    payload["pose_validated_yolov8"] = (
        validation_payload
        if is_yolov8_method(method)
        else validate_yolo_detections_for_pose([], pose_result.get("selected_person") if pose_result else None, image_shape, config)
    )

    active_summary = validation_payload.get("summary", {})
    payload["raw_detection_count"] = int(active_summary.get("raw_count", 0) or 0)
    payload["pose_accepted_detection_count"] = int(active_summary.get("pose_accepted_count", 0) or 0)
    payload["final_unique_detection_count"] = int(active_summary.get("final_unique_count", active_summary.get("accepted_count", 0)) or 0)
    payload["duplicate_removed_count"] = int(active_summary.get("duplicate_removed_count", 0) or 0)
    payload["detector_trace"] = validation_payload.get("detector_trace")
    payload["final_detections"] = validation_payload.get("final_unique_per_class_detections", [])
    payload["removed_duplicate_detections"] = validation_payload.get("removed_duplicate_detections", [])
    payload["canonical_class_mapping"] = {str(index): name for index, name in enumerate(REQUIRED_ITEM_KEYS)}
    payload["detector_model_id"] = validation_payload.get("detector_model_id")
    payload["detector_model_version"] = validation_payload.get("detector_model_version")
    payload["detector_confidence_threshold"] = validation_payload.get("detector_confidence_threshold")
    final_image_url = validation_payload.get("final_annotated_image_url") or validation_payload.get("annotated_image_url")
    final_image_path = validation_payload.get("final_annotated_image_path")
    payload["processed_image_url"] = final_image_url
    payload["final_annotated_image_url"] = final_image_url
    payload["final_annotated_image_path"] = final_image_path
    return payload


def run_lightweight_uniform_method_candidate(
    method: str,
    image_path: Path,
    pre_ai_info: dict,
    pose_result: dict | None,
    yolo_confidence=None,
    yolo_image_size=None,
    insightface_executed: bool = False,
) -> dict:
    timestamp = utc_now_iso()
    prefix = METHOD_FILENAME_PREFIXES.get(method, method)
    image = load_rgb_image(image_path)
    image_shape = image_shape_from_pil(image)
    notes = [
        "Lightweight no-SCHP/no-Florence evaluation started.",
        "YOLOv8 Pose selected the target student/person before component scoring.",
    ]
    logger.info(
        "lightweight_uniform_evaluation_start method=%s image=%s schp=skipped florence2=skipped",
        method,
        image_path,
    )

    try:
        if is_yolov8_method(method):
            yolo_output, pose_result, validation_payload, validation_notes = run_pose_validated_yolov8(
                saved_path=image_path,
                image_shape=image_shape,
                yolo_confidence=yolo_confidence,
                yolo_image_size=yolo_image_size,
                yolo_save_annotated=False,
                pose_result=pose_result,
                filename_prefix=prefix,
            )
            validation_payload["detector_model_id"] = validation_payload.get("detector_model_id") or yolo_output.get("model_id")
            validation_payload["detector_model_version"] = validation_payload.get("detector_model_version") or yolo_output.get("model_version")
            validation_payload["detector_confidence_threshold"] = (
                validation_payload.get("detector_confidence_threshold") or yolo_output.get("confidence_threshold")
            )
            required_items = required_items_from_detections(
                validation_payload.get("accepted_detections", []),
                present_threshold=config.REQUIRED_ITEM_PRESENT_THRESHOLD,
            )
            yolov8_payload = public_yolov8_payload(yolo_output)
            yolov8_payload["annotated_image_url"] = validation_payload.get("annotated_image_url")
            grounding_payload = {
                "available": False,
                "skipped": True,
                "error": "GroundingDINO was not run for lightweight YOLOv8 uniform method.",
                "detections": [],
                "required_items": default_required_items(),
                "notes": [],
            }
            notes.extend(validation_notes)
            notes.append("Method 2 used pose-validated YOLOv8 uniform detections.")
        else:
            grounding_output = grounding_service.detect_required_items(image)
            grounding_detections = grounding_detections_to_uniform_detections(grounding_output)
            validation_payload = validate_yolo_detections_for_pose(
                grounding_detections,
                pose_result.get("selected_person") if pose_result else None,
                image_shape,
                config,
            )
            validation_payload["raw_grounding_dino_detections"] = validation_payload.get(
                "raw_yolo_detections",
                grounding_detections,
            )
            validation_payload["source_detector"] = grounding_service.SOURCE_DETECTOR
            validation_payload["detector_model_id"] = grounding_service.model_id
            validation_payload["detector_model_version"] = grounding_service.model_id
            validation_payload["detector_confidence_threshold"] = {
                "box": config.GROUNDING_BOX_THRESHOLD,
                "text": config.GROUNDING_TEXT_THRESHOLD,
            }
            annotated_path = save_pose_validation_visualization(
                image_path=image_path,
                selected_person=pose_result.get("selected_person") if pose_result else None,
                accepted_detections=validation_payload["accepted_detections"],
                rejected_detections=validation_payload["rejected_detections"],
                output_dir=config.YOLO_OUTPUT_DIR,
                min_keypoint_confidence=config.POSE_MIN_CONFIDENCE,
                show_rejected=config.SHOW_REJECTED_UNIFORM_DETECTIONS,
                filename_prefix=prefix,
            )
            final_image_url = yolov8_output_url(annotated_path.name)
            validation_payload["annotated_image_url"] = final_image_url
            validation_payload["final_annotated_image_url"] = final_image_url
            validation_payload["processed_image_url"] = final_image_url
            validation_payload["final_annotated_image_path"] = str(annotated_path)
            required_items = required_items_from_detections(
                validation_payload.get("accepted_detections", []),
                present_threshold=config.REQUIRED_ITEM_PRESENT_THRESHOLD,
            )
            grounding_payload = {
                "available": True,
                "model": grounding_service.model_id,
                "detections": grounding_output.get("detections", []),
                "uniform_detections": grounding_detections,
                "required_items": grounding_output.get("required_items", default_required_items()),
                "notes": grounding_output.get("notes", []),
            }
            yolov8_payload = {
                "available": False,
                "skipped": True,
                "error": "YOLOv8 uniform model was not run for lightweight Grounding DINO method.",
                "detections": [],
                "summary": {"total_detections": 0, "detected_classes": []},
            }
            if grounding_output.get("notes"):
                notes.extend(grounding_output.get("notes", []))
            notes.append("Method 1 used pose-validated Grounding DINO detections.")
    except Exception as exc:
        logger.exception("lightweight_uniform_evaluation_failed method=%s image=%s", method, image_path)
        raise RuntimeError(f"LIGHTWEIGHT_DETECTOR_FAILED: {method}: {exc}") from exc
    finally:
        if is_grounding_method(method):
            grounding_service.release()

    legacy_payload = _lightweight_component_payload(
        method=method,
        image_path=image_path,
        image_shape=image_shape,
        pose_result=pose_result,
        required_items=required_items,
        validation_payload=validation_payload,
        grounding_payload=grounding_payload,
        yolov8_payload=yolov8_payload,
        notes=notes,
        insightface_executed=insightface_executed,
    )
    candidate = _candidate_from_legacy_payload(method, legacy_payload, image_path, pre_ai_info, timestamp)
    candidate["pipeline"] = legacy_payload["pipeline"]
    candidate["lightweight_no_schp_no_florence"] = True
    logger.info(
        "lightweight_uniform_evaluation_completed method=%s image=%s schp=skipped florence2=skipped score=%s status=%s",
        method,
        image_path,
        candidate.get("score"),
        candidate.get("result_status"),
    )
    return candidate


def single_uniform_evaluation_payload(
    pre_ai_image_path: Path,
    pre_ai_info: dict,
    candidate: dict,
    pose_result: dict | None,
    pose_notes: list[str] | None = None,
) -> dict:
    legacy_default = candidate.get("legacy_result", {})
    evaluation_id = f"uniform_{datetime.utcnow().strftime('%Y%m%d_%H%M%S_%f')}_{uuid4().hex[:8]}"
    payload = {
        "success": True,
        "evaluation_id": evaluation_id,
        "created_at": utc_now_iso(),
        "pre_ai_image": str(pre_ai_image_path),
        "pre_ai_image_url": pre_ai_image_url(pre_ai_image_path),
        "pre_ai_image_metadata": pre_ai_info,
        "pose": public_pose_payload(pose_result),
        "pose_notes": pose_notes or [],
        "candidates": [candidate],
        "candidate_methods": [candidate["method"]],
        "default_candidate_method": candidate["method"],
        "admin_selection": {
            "endpoint": "/api/uniform/admin/select-evaluation",
            "compatible_endpoint": "/api/admin/select-uniform-evaluation",
            "input": {
                "evaluation_id": evaluation_id,
                "selected_method": candidate["method"],
            },
        },
    }

    for key, value in legacy_default.items():
        if key not in payload:
            payload[key] = value
    payload["processed_image_url"] = candidate.get("processed_image_url")
    payload["final_annotated_image_url"] = candidate.get("processed_image_url")
    payload["final_annotated_image_path"] = candidate.get("processed_image")

    record_path = uniform_evaluation_repository.save_evaluation(payload)
    payload["record_path"] = str(record_path)
    return payload


def run_dual_uniform_evaluation(
    pre_ai_image_path: Path,
    pre_ai_info: dict,
    yolo_confidence=None,
    yolo_image_size=None,
    yolo_save_annotated=None,
    pose_result: dict | None = None,
) -> dict:
    image = load_rgb_image(pre_ai_image_path)
    image_shape = image_shape_from_pil(image)
    pose_notes: list[str] = []
    if pose_result is None:
        pose_result, pose_notes = estimate_pose_for_image(pre_ai_image_path, image_shape)

    candidates = [
        run_uniform_method_candidate(
            METHOD_GROUNDING_DINO,
            pre_ai_image_path,
            pre_ai_info,
            pose_result,
            yolo_confidence=yolo_confidence,
            yolo_image_size=yolo_image_size,
            yolo_save_annotated=yolo_save_annotated,
        ),
        run_uniform_method_candidate(
            METHOD_YOLOV8,
            pre_ai_image_path,
            pre_ai_info,
            pose_result,
            yolo_confidence=yolo_confidence,
            yolo_image_size=yolo_image_size,
            yolo_save_annotated=yolo_save_annotated,
        ),
    ]
    default_candidate = next((candidate for candidate in candidates if candidate["method"] == METHOD_YOLOV8), candidates[0])
    legacy_default = default_candidate.get("legacy_result", {})

    evaluation_id = f"uniform_{datetime.utcnow().strftime('%Y%m%d_%H%M%S_%f')}_{uuid4().hex[:8]}"
    payload = {
        "success": True,
        "evaluation_id": evaluation_id,
        "created_at": utc_now_iso(),
        "pre_ai_image": str(pre_ai_image_path),
        "pre_ai_image_url": pre_ai_image_url(pre_ai_image_path),
        "pre_ai_image_metadata": pre_ai_info,
        "pose": public_pose_payload(pose_result),
        "pose_notes": pose_notes,
        "candidates": candidates,
        "candidate_methods": [candidate["method"] for candidate in candidates],
        "default_candidate_method": default_candidate["method"],
        "admin_selection": {
            "endpoint": "/api/uniform/admin/select-evaluation",
            "compatible_endpoint": "/api/admin/select-uniform-evaluation",
            "input": {
                "evaluation_id": evaluation_id,
                "selected_method": [METHOD_GROUNDING_DINO, METHOD_YOLOV8],
            },
        },
    }

    for key, value in legacy_default.items():
        if key not in payload:
            payload[key] = value
    payload["processed_image_url"] = default_candidate.get("processed_image_url")
    payload["final_annotated_image_url"] = default_candidate.get("processed_image_url")
    payload["final_annotated_image_path"] = default_candidate.get("processed_image")

    record_path = uniform_evaluation_repository.save_evaluation(payload)
    payload["record_path"] = str(record_path)
    return payload


def run_lightweight_uniform_evaluation(
    pre_ai_image_path: Path,
    pre_ai_info: dict,
    yolo_confidence=None,
    yolo_image_size=None,
    pose_result: dict | None = None,
    selected_method: str | None = None,
    insightface_executed: bool = False,
) -> dict:
    method = normalize_lightweight_method(selected_method)
    image = load_rgb_image(pre_ai_image_path)
    image_shape = image_shape_from_pil(image)
    pose_notes: list[str] = []
    if pose_result is None:
        pose_result, pose_notes = estimate_pose_for_image(pre_ai_image_path, image_shape)

    executed_modules, skipped_modules = _lightweight_module_trace(method, insightface_executed)
    logger.info(
        "lightweight_method=%s executed=%s skipped=%s processed_image_output_path=%s status=started "
        "image=%s pose_selected=%s",
        method,
        ",".join(executed_modules),
        ",".join(skipped_modules),
        None,
        pre_ai_image_path,
        bool(pose_result.get("selected")) if pose_result else False,
    )

    try:
        candidate = run_lightweight_uniform_method_candidate(
            method,
            pre_ai_image_path,
            pre_ai_info,
            pose_result,
            yolo_confidence=yolo_confidence,
            yolo_image_size=yolo_image_size,
            insightface_executed=insightface_executed,
        )
    except Exception as exc:
        logger.exception(
            "lightweight_method=%s executed=%s skipped=%s processed_image_output_path=%s status=failed "
            "image=%s error=%s",
            method,
            ",".join(executed_modules),
            ",".join(skipped_modules),
            None,
            pre_ai_image_path,
            exc,
        )
        raise

    candidates = [candidate]
    default_candidate = candidate
    legacy_default = default_candidate.get("legacy_result", {})
    component_detector = "grounding_dino_v2" if is_grounding_method(method) else "yolov8_v2"

    evaluation_id = f"uniform_lightweight_{datetime.utcnow().strftime('%Y%m%d_%H%M%S_%f')}_{uuid4().hex[:8]}"
    payload = {
        "success": True,
        "evaluation_id": evaluation_id,
        "created_at": utc_now_iso(),
        "pre_ai_image": str(pre_ai_image_path),
        "pre_ai_image_url": pre_ai_image_url(pre_ai_image_path),
        "pre_ai_image_metadata": pre_ai_info,
        "pose": public_pose_payload(pose_result),
        "pose_notes": pose_notes,
        "pipeline": {
            "name": "no_schp_no_florence_uniform_evaluation",
            "uses_schp": False,
            "uses_florence2": False,
            "uses_pose": True,
            "uses_insightface": bool(insightface_executed),
            "methods": [method],
            "selected_method": method,
            "component_detector": component_detector,
            "component_detectors": [component_detector],
        },
        "lightweight_no_schp_no_florence": True,
        "skipped_models": {
            "schp": "SCHP intentionally skipped for lightweight evaluation.",
            "florence2": "Florence-2 intentionally skipped for lightweight evaluation.",
        },
        "candidates": candidates,
        "candidate_methods": [candidate["method"] for candidate in candidates],
        "default_candidate_method": default_candidate["method"],
        "admin_selection": {
            "endpoint": "/api/uniform/admin/select-evaluation",
            "compatible_endpoint": "/api/admin/select-uniform-evaluation",
            "input": {
                "evaluation_id": evaluation_id,
                "selected_method": method,
            },
        },
    }
    if is_grounding_method(method):
        payload["method_1_result"] = candidate
    else:
        payload["method_2_result"] = candidate

    for key, value in legacy_default.items():
        if key not in payload:
            payload[key] = value
    payload["processed_image_url"] = default_candidate.get("processed_image_url")
    payload["final_annotated_image_url"] = default_candidate.get("processed_image_url")
    payload["final_annotated_image_path"] = default_candidate.get("processed_image")

    try:
        record_path = uniform_evaluation_repository.save_evaluation(payload)
    except Exception as exc:
        logger.exception(
            "lightweight_method=%s executed=%s skipped=%s processed_image_output_path=%s status=failed "
            "evaluation_id=%s image=%s error=%s",
            method,
            ",".join(executed_modules),
            ",".join(skipped_modules),
            candidate.get("processed_image"),
            evaluation_id,
            pre_ai_image_path,
            exc,
        )
        raise
    payload["record_path"] = str(record_path)
    logger.info(
        "lightweight_method=%s executed=%s skipped=%s processed_image_output_path=%s status=completed "
        "evaluation_id=%s image=%s",
        method,
        ",".join(executed_modules),
        ",".join(skipped_modules),
        candidate.get("processed_image"),
        evaluation_id,
        pre_ai_image_path,
    )
    return payload


@app.get("/api/uniform/health")
def health():
    return json_response(uniform_health_data())


@app.get("/api/ai/health")
def ai_health():
    return ai_success_response(
        {
            "status": "ok",
            "service": "uniform-ai-integrated",
            "port": config.PORT,
            "modules": {
                "uniform": uniform_health_data(),
                "yolov8": yolov8_service.status(),
                "pose": pose_estimator.status(),
                "face": face_health_data(),
            },
        },
        message="AI server is healthy",
    )


@app.get("/api/uniform/yolov8/outputs/<path:filename>")
def serve_yolov8_output(filename: str):
    return send_from_directory(config.YOLO_OUTPUT_DIR, filename, conditional=True)


@app.get("/api/uniform/outputs/<path:filename>")
@app.get("/static/outputs/<path:filename>")
def serve_uniform_output(filename: str):
    return send_from_directory(config.OUTPUT_DIR, filename, conditional=True)


@app.get("/api/uniform/pre-ai/<path:filename>")
def serve_pre_ai_image(filename: str):
    return send_from_directory(config.UNIFORM_PRE_AI_IMAGE_DIR, filename, conditional=True)


@app.post("/api/realtime-camera/analyze-frame")
def analyze_realtime_camera_frame():
    payload, status_code = analyze_realtime_camera_request(
        request,
        config,
        app.extensions["face_engine"],
        app.extensions["student_repository"],
    )
    return json_response(payload, status_code=status_code)


@app.post("/api/uniform/yolov8/predict")
def predict_yolov8():
    upload, validation_error = validate_image_upload()
    if validation_error:
        return validation_error

    try:
        assert upload is not None
        pre_ai = store_pre_ai_upload(
            upload,
            config.UNIFORM_PRE_AI_IMAGE_DIR,
            min_bytes=config.PRE_AI_IMAGE_MIN_BYTES,
            max_bytes=config.PRE_AI_IMAGE_MAX_BYTES,
        )
        saved_path = pre_ai.path
        image = load_rgb_image(saved_path)

        if parse_bool(request.form.get("validate_pose"), default=True):
            prediction, pose_result, validation_payload, validation_notes = run_pose_validated_yolov8(
                saved_path=saved_path,
                image_shape=image_shape_from_pil(image),
                yolo_confidence=request.form.get("confidence"),
                yolo_image_size=request.form.get("image_size"),
                yolo_save_annotated=request.form.get("save_annotated"),
            )
        else:
            prediction = yolov8_service.predict(
                saved_path,
                confidence=request.form.get("confidence"),
                image_size=request.form.get("image_size"),
                save_annotated=request.form.get("save_annotated"),
            )
            pose_result = None
            validation_payload = None
            validation_notes = []
        payload = public_yolov8_payload(prediction)
        if validation_payload is not None:
            final_image_url = validation_payload.get("final_annotated_image_url") or validation_payload.get("annotated_image_url")
            payload["pose"] = public_pose_payload(pose_result)
            payload["pose_validated_yolov8"] = validation_payload
            payload["annotated_image_url"] = final_image_url
            payload["processed_image_url"] = final_image_url
            payload["final_annotated_image_url"] = final_image_url
            payload["final_annotated_image_path"] = validation_payload.get("final_annotated_image_path")
            payload["notes"] = validation_notes
        payload["success"] = True
        payload["pre_ai_image"] = str(saved_path)
        payload["pre_ai_image_url"] = pre_ai_image_url(saved_path)
        payload["pre_ai_image_metadata"] = pre_ai.to_dict()
        payload["original_image_url"] = pre_ai_image_url(saved_path)
        return json_response(payload)
    except ValueError as exc:
        return error_response(str(exc), status_code=400)
    except Exception as exc:
        return error_response("YOLOv8 prediction failed.", status_code=503, details={"error": str(exc)})


def run_uniform_evaluation(
    saved_path: Path,
    yolo_confidence=None,
    yolo_image_size=None,
    yolo_save_annotated=None,
    pose_result: dict | None = None,
    component_method: str = "hybrid",
    filename_prefix: str | None = None,
    strict_detector: bool = False,
) -> dict:
    component_method = parse_uniform_method(component_method)
    stage_errors: list[str] = []
    notes: list[str] = []

    required_items = default_required_items()

    appearance = {
        "tucked_in": {"label": "uncertain", "score": 0.0},
        "wrinkled": {"label": "uncertain", "score": 0.0},
        "dirty": {"label": "uncertain", "score": 0.0},
        "torn_or_damaged": {"label": "uncertain", "score": 0.0},
    }

    grounding_ok = False
    parsing_ok = False
    florence_ok = False
    yolov8_ok = False
    grounding_validation_ok = False
    pose_ok = False
    grounding_payload = {
        "available": False,
        "error": "GroundingDINO was not run.",
        "detections": [],
        "required_items": default_required_items(),
        "notes": [],
    }
    yolov8_payload = {
        "available": False,
        "error": "YOLOv8 was not run.",
        "detections": [],
        "summary": {"total_detections": 0, "detected_classes": []},
    }
    pose_validation_payload = validate_yolo_detections_for_pose([], None, (1, 1, 3), config)
    grounding_validation_payload = validate_yolo_detections_for_pose([], None, (1, 1, 3), config)

    image = load_rgb_image(saved_path)
    image_shape = image_shape_from_pil(image)
    parsing_result = None
    parsing_public = {
        "available": False,
        "tucked_in": {"label": "uncertain", "score": 0.0},
        "bboxes": {},
        "notes": [],
    }
    florence_payload = {
        "available": False,
        "appearance": {
            "wrinkled": {"label": "uncertain", "score": 0.0},
            "dirty": {"label": "uncertain", "score": 0.0},
            "torn_or_damaged": {"label": "uncertain", "score": 0.0},
        },
        "description": None,
        "notes": [],
    }
    target_image = image

    if pose_result is None:
        pose_result, pose_notes = estimate_pose_for_image(saved_path, image_shape)
        notes.extend(pose_notes)

    selected_person = pose_result.get("selected_person") if pose_result else None
    if selected_person:
        pose_ok = True
        try:
            target_regions = build_pose_regions(
                selected_person,
                image_shape,
                min_confidence=config.POSE_MIN_CONFIDENCE,
                padding_ratio=config.TARGET_PERSON_PADDING_RATIO,
            )
            target_image = crop_image_to_bbox(image, target_regions.get("body_bbox"))
        except Exception as exc:
            stage_errors.append(f"Selected-pose crop failed: {exc}")
    else:
        stage_errors.append(pose_result.get("reason") or "No person pose detected.")

    if component_method in {"hybrid", "method_2"}:
        try:
            yolov8_output, pose_result, pose_validation_payload, validation_notes = run_pose_validated_yolov8(
                saved_path=saved_path,
                image_shape=image_shape,
                yolo_confidence=yolo_confidence,
                yolo_image_size=yolo_image_size,
                yolo_save_annotated=yolo_save_annotated,
                pose_result=pose_result,
                filename_prefix=filename_prefix,
            )
            yolov8_payload = public_yolov8_payload(yolov8_output)
            yolov8_payload["annotated_image_url"] = pose_validation_payload.get("annotated_image_url")
            if component_method in {"hybrid", "method_2"}:
                required_items = required_items_from_detections(
                    pose_validation_payload.get("accepted_detections", []),
                    present_threshold=config.REQUIRED_ITEM_PRESENT_THRESHOLD,
                )
            notes.extend(validation_notes)
            notes.append("Uniform component scoring used pose-validated YOLOv8 detections.")
            yolov8_ok = True
        except Exception as exc:
            if strict_detector and component_method == "method_2":
                raise RuntimeError(f"YOLOV8_V2_MODEL_LOAD_FAILED: {exc}") from exc
            yolov8_payload = {
                "available": False,
                "error": str(exc),
                "detections": [],
                "summary": {"total_detections": 0, "detected_classes": []},
            }
            stage_errors.append(f"YOLOv8 inference failed: {exc}")

    if component_method in {"hybrid", "method_1"}:
        try:
            grounding_output = grounding_service.detect_required_items(image)
            grounding_detections = grounding_detections_to_uniform_detections(grounding_output)
            grounding_validation_payload = validate_yolo_detections_for_pose(
                grounding_detections,
                pose_result.get("selected_person") if pose_result else None,
                image_shape,
                config,
            )
            grounding_validation_payload["raw_grounding_dino_detections"] = grounding_validation_payload.get(
                "raw_yolo_detections",
                grounding_detections,
            )
            grounding_validation_payload["source_detector"] = grounding_service.SOURCE_DETECTOR
            grounding_validation_payload["detector_model_id"] = grounding_service.model_id
            grounding_validation_payload["detector_model_version"] = grounding_service.model_id
            grounding_validation_payload["detector_confidence_threshold"] = {
                "box": config.GROUNDING_BOX_THRESHOLD,
                "text": config.GROUNDING_TEXT_THRESHOLD,
            }
            if component_method == "method_1":
                required_items = required_items_from_detections(
                    grounding_validation_payload.get("accepted_detections", []),
                    present_threshold=config.REQUIRED_ITEM_PRESENT_THRESHOLD,
                )
                annotated_path = save_pose_validation_visualization(
                    image_path=saved_path,
                    selected_person=pose_result.get("selected_person") if pose_result else None,
                    accepted_detections=grounding_validation_payload["accepted_detections"],
                    rejected_detections=grounding_validation_payload["rejected_detections"],
                    output_dir=config.YOLO_OUTPUT_DIR,
                    min_keypoint_confidence=config.POSE_MIN_CONFIDENCE,
                    show_rejected=config.SHOW_REJECTED_UNIFORM_DETECTIONS,
                    filename_prefix=filename_prefix,
                )
                final_image_url = yolov8_output_url(annotated_path.name)
                grounding_validation_payload["annotated_image_url"] = final_image_url
                grounding_validation_payload["final_annotated_image_url"] = final_image_url
                grounding_validation_payload["processed_image_url"] = final_image_url
                grounding_validation_payload["final_annotated_image_path"] = str(annotated_path)

            grounding_payload = {
                "available": True,
                "model": grounding_service.model_id,
                "detections": grounding_output.get("detections", []),
                "uniform_detections": grounding_detections,
                "required_items": grounding_output.get("required_items", default_required_items()),
                "notes": grounding_output.get("notes", []),
            }
            if grounding_output.get("notes"):
                notes.extend(grounding_output.get("notes", []))
            notes.append("GroundingDINO check ran against the full image and was filtered to the selected pose.")
            grounding_ok = True
            grounding_validation_ok = True
        except Exception as exc:
            if strict_detector and component_method == "method_1":
                raise RuntimeError(f"GROUNDING_DINO_V2_MODEL_LOAD_FAILED: {exc}") from exc
            grounding_payload = {
                "available": False,
                "error": str(exc),
                "detections": [],
                "required_items": default_required_items(),
                "notes": [],
            }
            stage_errors.append(f"GroundingDINO inference failed: {exc}")
        finally:
            grounding_service.release()

    try:
        if component_method == "hybrid" and grounding_ok:
            grounding_output = grounding_payload
            if grounding_output.get("notes"):
                notes.append("GroundingDINO check ran on the selected target crop for review notes.")
                notes.extend(grounding_output.get("notes", []))
    except Exception as exc:
        stage_errors.append(f"GroundingDINO review notes failed: {exc}")

    if component_method in {"method_1", "method_2"}:
        visualization_payload = grounding_validation_payload if component_method == "method_1" else pose_validation_payload
        if not visualization_payload.get("final_annotated_image_path"):
            try:
                annotated_path = save_pose_validation_visualization(
                    image_path=saved_path,
                    selected_person=pose_result.get("selected_person") if pose_result else None,
                    accepted_detections=visualization_payload.get("accepted_detections", []),
                    rejected_detections=visualization_payload.get("rejected_detections", []),
                    output_dir=config.YOLO_OUTPUT_DIR,
                    min_keypoint_confidence=config.POSE_MIN_CONFIDENCE,
                    show_rejected=config.SHOW_REJECTED_UNIFORM_DETECTIONS,
                    filename_prefix=filename_prefix,
                )
                final_image_url = yolov8_output_url(annotated_path.name)
                visualization_payload["annotated_image_url"] = final_image_url
                visualization_payload["final_annotated_image_url"] = final_image_url
                visualization_payload["processed_image_url"] = final_image_url
                visualization_payload["final_annotated_image_path"] = str(annotated_path)
            except Exception as exc:
                stage_errors.append(f"Final visualization failed: {exc}")

    try:
        parsing_result = parsing_service.parse(target_image)
        appearance["tucked_in"] = parsing_result["tucked_in"]
        notes.extend(parsing_result.get("notes", []))
        parsing_public = {
            "available": True,
            "tucked_in": parsing_result.get("tucked_in", {"label": "uncertain", "score": 0.0}),
            "bboxes": parsing_result.get("bboxes", {}),
            "notes": parsing_result.get("notes", []),
        }
        parsing_ok = True
    except Exception as exc:
        parsing_public = {
            "available": False,
            "error": str(exc),
            "tucked_in": {"label": "uncertain", "score": 0.0},
            "bboxes": {},
            "notes": [],
        }
        stage_errors.append(f"SCHP ATR parsing failed: {exc}")
    finally:
        parsing_service.release()

    try:
        florence_output = florence_service.evaluate_appearance(target_image, parsing_result)
        appearance.update(florence_output["appearance"])
        notes.extend(florence_output.get("notes", []))
        florence_payload = {
            "available": True,
            "appearance": florence_output.get("appearance", {}),
            "description": florence_output.get("description"),
            "caption_text": florence_output.get("caption_text"),
            "visual_metrics": florence_output.get("visual_metrics", {}),
            "notes": florence_output.get("notes", []),
        }
        florence_ok = True
    except Exception as exc:
        florence_payload = {
            "available": False,
            "error": str(exc),
            "appearance": {
                "wrinkled": {"label": "uncertain", "score": 0.0},
                "dirty": {"label": "uncertain", "score": 0.0},
                "torn_or_damaged": {"label": "uncertain", "score": 0.0},
            },
            "description": None,
            "notes": [],
        }
        stage_errors.append(f"Florence-2 appearance analysis failed: {exc}")
    finally:
        florence_service.release()

    if not (pose_ok or yolov8_ok or grounding_ok or parsing_ok or florence_ok):
        raise RuntimeError(f"Uniform evaluation failed at all model stages: {'; '.join(stage_errors)}")

    if stage_errors:
        notes.extend(stage_errors)

    payload = rule_engine.aggregate(
        required_items=required_items,
        appearance=appearance,
        notes=notes,
    )
    payload["pose"] = public_pose_payload(pose_result)
    payload["selected_person"] = payload["pose"].get("selected_person")
    payload["component_method"] = component_method
    payload["grounding_dino"] = grounding_payload
    payload["schp"] = parsing_public
    payload["florence2"] = florence_payload
    payload["pose_validated_grounding_dino"] = grounding_validation_payload
    payload["yolov8"] = yolov8_payload
    payload["pose_validated_yolov8"] = pose_validation_payload
    if component_method == "method_1":
        active_validation_payload = grounding_validation_payload
        payload["evaluation_method"] = METHOD_GROUNDING_DINO
        payload["detector_model_id"] = grounding_validation_payload.get("detector_model_id") or grounding_service.model_id
        payload["detector_model_version"] = grounding_validation_payload.get("detector_model_version") or grounding_service.model_id
        payload["detector_confidence_threshold"] = grounding_validation_payload.get("detector_confidence_threshold")
        final_image_url = grounding_validation_payload.get("final_annotated_image_url") or grounding_validation_payload.get("annotated_image_url")
        final_image_path = grounding_validation_payload.get("final_annotated_image_path")
    else:
        active_validation_payload = pose_validation_payload
        payload["evaluation_method"] = METHOD_YOLOV8 if component_method == "method_2" else "LEGACY"
        payload["detector_model_id"] = pose_validation_payload.get("detector_model_id") or yolov8_payload.get("model_id")
        payload["detector_model_version"] = pose_validation_payload.get("detector_model_version") or yolov8_payload.get("model_version")
        payload["detector_confidence_threshold"] = pose_validation_payload.get("detector_confidence_threshold") or yolov8_payload.get("confidence_threshold")
        final_image_url = pose_validation_payload.get("final_annotated_image_url") or pose_validation_payload.get("annotated_image_url")
        final_image_path = pose_validation_payload.get("final_annotated_image_path")
    active_summary = active_validation_payload.get("summary", {})
    payload["raw_detection_count"] = int(active_summary.get("raw_count", 0) or 0)
    payload["pose_accepted_detection_count"] = int(active_summary.get("pose_accepted_count", 0) or 0)
    payload["final_unique_detection_count"] = int(active_summary.get("final_unique_count", active_summary.get("accepted_count", 0)) or 0)
    payload["duplicate_removed_count"] = int(active_summary.get("duplicate_removed_count", 0) or 0)
    payload["detector_trace"] = active_validation_payload.get("detector_trace")
    payload["final_detections"] = active_validation_payload.get("final_unique_per_class_detections", [])
    payload["removed_duplicate_detections"] = active_validation_payload.get("removed_duplicate_detections", [])
    payload["canonical_class_mapping"] = {str(index): name for index, name in enumerate(REQUIRED_ITEM_KEYS)}
    payload["processed_image_url"] = final_image_url
    payload["final_annotated_image_url"] = final_image_url
    payload["final_annotated_image_path"] = final_image_path
    return payload


@app.post("/api/uniform/evaluate")
@app.post("/evaluate-uniform")
def evaluate_uniform():
    upload, validation_error = validate_image_upload()
    if validation_error:
        return validation_error

    try:
        assert upload is not None
        pre_ai = store_pre_ai_upload(
            upload,
            config.UNIFORM_PRE_AI_IMAGE_DIR,
            min_bytes=config.PRE_AI_IMAGE_MIN_BYTES,
            max_bytes=config.PRE_AI_IMAGE_MAX_BYTES,
        )
        payload = run_dual_uniform_evaluation(
            pre_ai.path,
            pre_ai.to_dict(),
            yolo_confidence=request.form.get("confidence"),
            yolo_image_size=request.form.get("image_size"),
            yolo_save_annotated=request.form.get("save_annotated"),
        )
        return json_response(payload)
    except RuntimeError as exc:
        return error_response("Uniform evaluation failed.", status_code=500, details={"error": str(exc)})
    except ValueError as exc:
        return error_response(str(exc), status_code=400)
    except Exception as exc:
        return error_response(
            "Unexpected server error during uniform evaluation.",
            status_code=500,
            details={"error": str(exc)},
        )


@app.post("/api/uniform/evaluate/lightweight")
def evaluate_uniform_lightweight():
    upload, validation_error = validate_image_upload()
    if validation_error:
        return validation_error

    try:
        lightweight_method = lightweight_method_from_request()
        assert upload is not None
        pre_ai = store_pre_ai_upload(
            upload,
            config.UNIFORM_PRE_AI_IMAGE_DIR,
            min_bytes=config.PRE_AI_IMAGE_MIN_BYTES,
            max_bytes=config.PRE_AI_IMAGE_MAX_BYTES,
        )
        payload = run_lightweight_uniform_evaluation(
            pre_ai.path,
            pre_ai.to_dict(),
            yolo_confidence=request.form.get("confidence") or request.form.get("uniform_confidence"),
            yolo_image_size=request.form.get("image_size") or request.form.get("uniform_image_size"),
            selected_method=lightweight_method,
            insightface_executed=False,
        )
        return json_response(payload)
    except RuntimeError as exc:
        return error_response("Lightweight uniform evaluation failed.", status_code=500, details={"error": str(exc)})
    except ValueError as exc:
        return error_response(str(exc), status_code=400)
    except Exception as exc:
        return error_response(
            "Unexpected server error during lightweight uniform evaluation.",
            status_code=500,
            details={"error": str(exc)},
        )


def _structured_error_code(message: str, fallback: str = "EVALUATION_PIPELINE_FAILED") -> str:
    text = str(message or "")
    if ":" in text:
        code = text.split(":", 1)[0].strip()
        if code.isupper() and all(ch.isalnum() or ch == "_" for ch in code):
            return code
    return fallback


def _advanced_face_payload(saved_path: Path, pose_result: dict | None, student_id: str, face_mode: str) -> tuple[dict, bool, bool, list[str]]:
    reasons: list[str] = []
    identity_passed = True
    needs_review = False

    if not pose_result or not pose_result.get("selected"):
        return (
            {
                "ran": False,
                "mode": face_mode,
                "requested_student_id": student_id or None,
                "recognized_student_id": None,
                "needs_review": True,
                "reason": "selected_person_not_found",
            },
            False,
            True,
            ["selected_person_not_found"],
        )

    if face_mode == "none":
        return (
            {
                "ran": False,
                "mode": face_mode,
                "requested_student_id": student_id or None,
                "recognized_student_id": None,
                "skipped": True,
            },
            True,
            False,
            ["face_skipped"],
        )

    if face_mode == "verify":
        try:
            verification = verify_image_for_selected_pose(student_id, saved_path, pose_result=pose_result)
            identity_passed = bool(verification.get("face_matched_to_selected_pose") and verification.get("verified"))
            if not identity_passed:
                needs_review = True
                reasons.append("face_match_below_threshold")
            return (
                {
                    "ran": True,
                    "mode": "verify",
                    "requested_student_id": student_id or None,
                    "recognized_student_id": student_id if identity_passed else None,
                    "needs_review": needs_review,
                    **verification,
                },
                identity_passed,
                needs_review,
                reasons,
            )
        except FaceApiError as exc:
            return (
                {
                    "ran": True,
                    "mode": "verify",
                    "requested_student_id": student_id or None,
                    "recognized_student_id": None,
                    "needs_review": True,
                    "error": {"code": exc.code, "message": exc.message},
                },
                False,
                True,
                ["face_match_below_threshold"],
            )

    try:
        identification = identify_image_for_selected_pose(saved_path, pose_result=pose_result)
        identity_passed = bool(
            identification.get("face_matched_to_selected_pose") and _identify_has_match(identification)
        )
        recognized_student_id = None
        if identity_passed:
            best_match = identification.get("best_match") or {}
            recognized_student_id = best_match.get("student_id") or best_match.get("student", {}).get("student_id")
        if not identity_passed:
            needs_review = True
            reasons.append("face_not_identified")
        return (
            {
                "ran": True,
                "mode": "identify",
                "requested_student_id": student_id or None,
                "recognized_student_id": recognized_student_id,
                "needs_review": needs_review,
                **identification,
            },
            identity_passed,
            needs_review,
            reasons,
        )
    except FaceApiError as exc:
        return (
            {
                "ran": True,
                "mode": "identify",
                "requested_student_id": student_id or None,
                "recognized_student_id": None,
                "needs_review": True,
                "error": {"code": exc.code, "message": exc.message},
            },
            False,
            True,
            ["face_not_identified"],
        )


def _evaluate_advanced_method(component_method: str, evaluation_method: str):
    upload, validation_error = validate_image_upload()
    if validation_error:
        return validation_error

    student_id = (request.form.get("student_id") or "").strip()
    face_mode = (request.form.get("face_mode") or ("verify" if student_id else "identify")).strip().lower()
    if face_mode not in {"verify", "identify", "none"}:
        return error_response("face_mode must be one of: verify, identify, none.", status_code=400)
    if face_mode == "verify" and not student_id:
        return error_response("student_id is required when face_mode=verify.", status_code=400)

    try:
        assert upload is not None
        pre_ai = store_pre_ai_upload(
            upload,
            config.UNIFORM_PRE_AI_IMAGE_DIR,
            min_bytes=config.PRE_AI_IMAGE_MIN_BYTES,
            max_bytes=config.PRE_AI_IMAGE_MAX_BYTES,
        )
        saved_path = pre_ai.path
        image = load_rgb_image(saved_path)
        image_shape = image_shape_from_pil(image)
        pose_result, pose_notes = estimate_pose_for_image(saved_path, image_shape)
        face_payload, identity_passed, needs_review, face_reasons = _advanced_face_payload(
            saved_path,
            pose_result,
            student_id,
            face_mode,
        )

        legacy_payload = run_uniform_evaluation(
            saved_path,
            yolo_confidence=request.form.get("confidence") or request.form.get("uniform_confidence"),
            yolo_image_size=request.form.get("image_size") or request.form.get("uniform_image_size"),
            yolo_save_annotated=request.form.get("save_annotated"),
            pose_result=pose_result,
            component_method=component_method,
            filename_prefix=METHOD_FILENAME_PREFIXES.get(evaluation_method, evaluation_method),
            strict_detector=True,
        )
        candidate = _candidate_from_legacy_payload(
            evaluation_method,
            legacy_payload,
            saved_path,
            pre_ai.to_dict(),
            utc_now_iso(),
        )
        payload = single_uniform_evaluation_payload(
            saved_path,
            pre_ai.to_dict(),
            candidate,
            pose_result,
            pose_notes,
        )
        payload["request"] = {
            "student_id": student_id or None,
            "face_mode": face_mode,
            "evaluation_method": evaluation_method,
        }
        payload["evaluation_method"] = evaluation_method
        payload["face"] = face_payload
        payload["final_decision"] = {
            "identity_passed": identity_passed,
            "uniform_passed": legacy_payload.get("overall", {}).get("compliance") in {"compliant", "partially_compliant"},
            "compliance": "needs_review" if needs_review else legacy_payload.get("overall", {}).get("compliance"),
            "should_allow": bool(identity_passed and not needs_review),
            "needs_review": bool(needs_review),
            "reasons": face_reasons,
        }
        uniform_evaluation_repository.save_evaluation(payload)
        return json_response(payload)
    except ValueError as exc:
        return error_response(str(exc), status_code=400)
    except RuntimeError as exc:
        code = _structured_error_code(str(exc))
        return error_response(
            "Advanced uniform evaluation failed.",
            status_code=503,
            details={"code": code, "message": str(exc).split(":", 1)[-1].strip()},
        )
    except Exception as exc:
        return error_response(
            "Unexpected server error during advanced uniform evaluation.",
            status_code=500,
            details={"code": "EVALUATION_PIPELINE_FAILED", "message": str(exc)},
        )


@app.post("/api/uniform/evaluate/yolov8-v2")
def evaluate_uniform_yolov8_v2():
    return _evaluate_advanced_method("method_2", METHOD_YOLOV8)


@app.post("/api/uniform/evaluate/grounding-dino-v2")
def evaluate_uniform_grounding_dino_v2():
    return _evaluate_advanced_method("method_1", METHOD_GROUNDING_DINO)


def _combined_image_upload():
    upload = request.files.get("image")
    if upload is None or not upload.filename:
        return None, face_error_response(
            "Missing image file. Use multipart key 'image'.",
            status_code=400,
            code="NO_FILE_UPLOADED",
        )
    if not is_allowed_image_file(upload.filename, config.ALLOWED_IMAGE_EXTENSIONS):
        ext_hint = ", ".join(sorted(config.ALLOWED_IMAGE_EXTENSIONS))
        return None, face_error_response(
            f"Unsupported image format. Allowed extensions: {ext_hint}.",
            status_code=400,
            code="INVALID_FILE_TYPE",
        )
    return upload, None


def _identify_has_match(face_data: dict) -> bool:
    best_match = face_data.get("best_match")
    if isinstance(best_match, dict) and best_match.get("matched"):
        return True

    for detection in face_data.get("detections", []):
        match = detection.get("match", {})
        if isinstance(match, dict) and match.get("matched"):
            return True
    return False


@app.post("/api/ai/evaluate-student")
def evaluate_student():
    upload, validation_error = _combined_image_upload()
    if validation_error:
        return validation_error

    student_id = (request.form.get("student_id") or "").strip()
    face_mode = (request.form.get("face_mode") or ("verify" if student_id else "identify")).strip().lower()
    run_face = parse_bool(request.form.get("run_face"), default=True)
    run_uniform = parse_bool(request.form.get("run_uniform"), default=True)
    try:
        uniform_method = parse_uniform_method(request.form.get("uniform_method") or "hybrid")
    except ValueError as exc:
        return face_error_response(
            str(exc),
            status_code=400,
            code="VALIDATION_ERROR",
            details={"uniform_method": request.form.get("uniform_method")},
        )

    if face_mode not in {"verify", "identify", "none"}:
        return face_error_response(
            "face_mode must be one of: verify, identify, none.",
            status_code=400,
            code="VALIDATION_ERROR",
            details={"face_mode": face_mode},
        )
    if run_face and face_mode == "verify" and not student_id:
        return face_error_response(
            "student_id is required when face_mode=verify.",
            status_code=400,
            code="VALIDATION_ERROR",
            details={"student_id": "required"},
        )

    saved_path: Path | None = None
    face_data: dict = {"ran": False, "mode": face_mode}
    uniform_data: dict = {"ran": False}
    pose_result: dict | None = None
    reasons: list[str] = []
    identity_passed = True
    uniform_passed = True
    needs_review = False

    try:
        assert upload is not None
        pre_ai = store_pre_ai_upload(
            upload,
            config.UNIFORM_PRE_AI_IMAGE_DIR,
            min_bytes=config.PRE_AI_IMAGE_MIN_BYTES,
            max_bytes=config.PRE_AI_IMAGE_MAX_BYTES,
        )
        saved_path = pre_ai.path
        image = load_rgb_image(saved_path)
        pose_result, pose_notes = estimate_pose_for_image(saved_path, image_shape_from_pil(image))
        if not pose_result.get("selected"):
            reasons.append("NO_SELECTED_POSE")

        if not run_face or face_mode == "none":
            reasons.append("FACE_SKIPPED")
            face_data = {"ran": False, "mode": face_mode, "skipped": True}
        elif face_mode == "verify":
            try:
                verification = verify_image_for_selected_pose(student_id, saved_path, pose_result=pose_result)
                identity_passed = bool(
                    verification.get("face_matched_to_selected_pose") and verification.get("verified")
                )
                face_data = {"ran": True, "mode": "verify", **verification}
                if not identity_passed:
                    reasons.append("FACE_NOT_MATCHED_TO_SELECTED_POSE")
            except FaceApiError as exc:
                identity_passed = False
                reasons.append("FACE_NOT_MATCHED")
                face_data = {
                    "ran": True,
                    "mode": "verify",
                    "error": {
                        "code": exc.code,
                        "message": exc.message,
                        "details": exc.details or {},
                    },
                }
        elif face_mode == "identify":
            try:
                identification = identify_image_for_selected_pose(saved_path, pose_result=pose_result)
                identity_passed = bool(
                    identification.get("face_matched_to_selected_pose") and _identify_has_match(identification)
                )
                face_data = {"ran": True, "mode": "identify", **identification}
                if not identity_passed:
                    reasons.append("UNKNOWN_SELECTED_FACE")
            except FaceApiError as exc:
                identity_passed = False
                reasons.append("UNKNOWN_FACE")
                face_data = {
                    "ran": True,
                    "mode": "identify",
                    "error": {
                        "code": exc.code,
                        "message": exc.message,
                        "details": exc.details or {},
                    },
                }

        if not run_uniform:
            reasons.append("UNIFORM_SKIPPED")
            uniform_data = {"ran": False, "skipped": True}
        else:
            try:
                if uniform_method in {"method_1", "method_2"}:
                    method_key = METHOD_GROUNDING_DINO if uniform_method == "method_1" else METHOD_YOLOV8
                    candidate = run_uniform_method_candidate(
                        method_key,
                        saved_path,
                        pre_ai.to_dict(),
                        pose_result,
                        yolo_confidence=request.form.get("uniform_confidence"),
                        yolo_image_size=request.form.get("uniform_image_size"),
                        yolo_save_annotated=request.form.get("save_annotated"),
                    )
                    uniform_payload = single_uniform_evaluation_payload(
                        saved_path,
                        pre_ai.to_dict(),
                        candidate,
                        pose_result,
                        pose_notes,
                    )
                else:
                    uniform_payload = run_dual_uniform_evaluation(
                        saved_path,
                        pre_ai.to_dict(),
                        yolo_confidence=request.form.get("uniform_confidence"),
                        yolo_image_size=request.form.get("uniform_image_size"),
                        yolo_save_annotated=request.form.get("save_annotated"),
                        pose_result=pose_result,
                    )
                uniform_data = {"ran": True, **uniform_payload}
                compliance = uniform_payload.get("overall", {}).get("compliance")
                if compliance == "needs_review":
                    needs_review = True
                    uniform_passed = False
                    reasons.append("UNIFORM_NEEDS_REVIEW")
                elif compliance not in {"compliant", "partially_compliant"}:
                    uniform_passed = False
                    reasons.append("UNIFORM_NON_COMPLIANT")
            except Exception as exc:
                uniform_passed = False
                reasons.append("UNIFORM_NON_COMPLIANT")
                uniform_data = {
                    "ran": True,
                    "error": {
                        "code": "UNIFORM_EVALUATION_FAILED",
                        "message": str(exc),
                    },
                }

        should_allow = bool(identity_passed and uniform_passed and not needs_review)
        if needs_review:
            final_compliance = "needs_review"
        elif should_allow:
            final_compliance = "compliant"
        else:
            final_compliance = "non_compliant"

        final_image_url = uniform_data.get("final_annotated_image_url") or uniform_data.get("processed_image_url")
        final_image_path = uniform_data.get("final_annotated_image_path")

        return ai_success_response(
            {
                "request": {
                    "face_mode": face_mode,
                    "run_face": run_face,
                    "run_uniform": run_uniform,
                    "student_id": student_id or None,
                },
                "pose": public_pose_payload(pose_result),
                "selected_person": public_pose_payload(pose_result).get("selected_person"),
                "processed_image_url": final_image_url,
                "final_annotated_image_url": final_image_url,
                "final_annotated_image_path": final_image_path,
                "face": face_data,
                "uniform": uniform_data,
                "final_decision": {
                    "identity_passed": identity_passed,
                    "uniform_passed": uniform_passed,
                    "compliance": final_compliance,
                    "should_allow": should_allow,
                    "needs_review": needs_review,
                    "reasons": reasons,
                },
            },
            message="AI evaluation completed",
        )
    except ValueError as exc:
        return face_error_response(str(exc), status_code=400, code="INVALID_IMAGE")
    except Exception as exc:
        return face_error_response(
            "Unexpected server error during AI evaluation.",
            status_code=500,
            code="AI_EVALUATION_FAILED",
            details={"error": str(exc)},
        )


def _recognized_student_payload(face_payload: dict) -> dict | None:
    if not isinstance(face_payload, dict):
        return None
    student = face_payload.get("student")
    if isinstance(student, dict) and student.get("student_id"):
        return student
    best_match = face_payload.get("best_match")
    if isinstance(best_match, dict):
        matched_student = best_match.get("student")
        if isinstance(matched_student, dict) and matched_student.get("student_id"):
            return matched_student
    for detection in face_payload.get("detections", []) or []:
        if not isinstance(detection, dict):
            continue
        match = detection.get("match")
        if isinstance(match, dict):
            matched_student = match.get("student")
            if isinstance(matched_student, dict) and matched_student.get("student_id"):
                return matched_student
    return None


@app.post("/api/ai/evaluate-student-lightweight")
def evaluate_student_lightweight():
    upload, validation_error = _combined_image_upload()
    if validation_error:
        return validation_error

    student_id = (request.form.get("student_id") or "").strip()
    face_mode = (request.form.get("face_mode") or ("verify" if student_id else "identify")).strip().lower()
    if face_mode not in {"verify", "identify", "none"}:
        return face_error_response(
            "face_mode must be one of: verify, identify, none.",
            status_code=400,
            code="VALIDATION_ERROR",
            details={"face_mode": face_mode},
        )
    if face_mode == "verify" and not student_id:
        return face_error_response(
            "student_id is required when face_mode=verify.",
            status_code=400,
            code="VALIDATION_ERROR",
            details={"student_id": "required"},
        )

    try:
        lightweight_method = lightweight_method_from_request()
    except ValueError as exc:
        return face_error_response(
            str(exc),
            status_code=400,
            code="VALIDATION_ERROR",
            details={"uniform_method": "invalid"},
        )

    try:
        assert upload is not None
        pre_ai = store_pre_ai_upload(
            upload,
            config.UNIFORM_PRE_AI_IMAGE_DIR,
            min_bytes=config.PRE_AI_IMAGE_MIN_BYTES,
            max_bytes=config.PRE_AI_IMAGE_MAX_BYTES,
        )
        saved_path = pre_ai.path
        image = load_rgb_image(saved_path)
        pose_result, pose_notes = estimate_pose_for_image(saved_path, image_shape_from_pil(image))
        face_payload, identity_passed, needs_review, face_reasons = _advanced_face_payload(
            saved_path,
            pose_result,
            student_id,
            face_mode,
        )
        uniform_payload = run_lightweight_uniform_evaluation(
            saved_path,
            pre_ai.to_dict(),
            yolo_confidence=request.form.get("uniform_confidence") or request.form.get("confidence"),
            yolo_image_size=request.form.get("uniform_image_size") or request.form.get("image_size"),
            pose_result=pose_result,
            selected_method=lightweight_method,
            insightface_executed=bool(face_payload.get("ran")),
        )
        uniform_payload["pose_notes"] = pose_notes

        compliance = uniform_payload.get("overall", {}).get("compliance")
        uniform_passed = compliance in {"compliant", "partially_compliant"}
        reasons = list(face_reasons)
        if compliance == "needs_review":
            needs_review = True
            reasons.append("UNIFORM_NEEDS_REVIEW")
        elif not uniform_passed:
            reasons.append("UNIFORM_NON_COMPLIANT")

        should_allow = bool(identity_passed and uniform_passed and not needs_review)
        if needs_review:
            final_compliance = "needs_review"
        elif should_allow:
            final_compliance = "compliant"
        else:
            final_compliance = "non_compliant"

        final_image_url = uniform_payload.get("final_annotated_image_url") or uniform_payload.get("processed_image_url")
        final_image_path = uniform_payload.get("final_annotated_image_path")
        recognized_student = _recognized_student_payload(face_payload)
        logger.info(
            "student_lightweight_evaluation_completed method=%s image=%s student_id=%s face_mode=%s schp=skipped florence2=skipped",
            lightweight_method,
            saved_path,
            student_id or None,
            face_mode,
        )

        return ai_success_response(
            {
                "request": {
                    "face_mode": face_mode,
                    "run_face": face_mode != "none",
                    "run_uniform": True,
                    "student_id": student_id or None,
                    "evaluation_mode": "lightweight_no_schp_no_florence",
                    "uniform_method": lightweight_method,
                },
                "pose": public_pose_payload(pose_result),
                "selected_person": public_pose_payload(pose_result).get("selected_person"),
                "processed_image_url": final_image_url,
                "final_annotated_image_url": final_image_url,
                "final_annotated_image_path": final_image_path,
                "face": face_payload,
                "recognized_student": recognized_student,
                "uniform": uniform_payload,
                "lightweight_no_schp_no_florence": True,
                "skipped_models": {
                    "schp": "SCHP intentionally skipped for lightweight evaluation.",
                    "florence2": "Florence-2 intentionally skipped for lightweight evaluation.",
                },
                "final_decision": {
                    "identity_passed": identity_passed,
                    "uniform_passed": uniform_passed,
                    "compliance": final_compliance,
                    "should_allow": should_allow,
                    "needs_review": needs_review,
                    "reasons": reasons,
                },
            },
            message="Lightweight AI evaluation completed without SCHP/FLORENCE",
        )
    except ValueError as exc:
        return face_error_response(str(exc), status_code=400, code="INVALID_IMAGE")
    except Exception as exc:
        return face_error_response(
            "Unexpected server error during lightweight AI evaluation.",
            status_code=500,
            code="LIGHTWEIGHT_AI_EVALUATION_FAILED",
            details={"error": str(exc)},
        )


def _selection_request_payload() -> dict:
    if request.is_json:
        payload = request.get_json(silent=True) or {}
    else:
        payload = request.form.to_dict() or {}
    return payload if isinstance(payload, dict) else {}


@app.post("/api/uniform/admin/select-evaluation")
@app.post("/api/admin/select-uniform-evaluation")
@app.post("/admin/select-uniform-evaluation")
def select_uniform_evaluation():
    payload = _selection_request_payload()
    evaluation_id = str(payload.get("evaluation_id") or "").strip()
    selected_method = str(payload.get("selected_method") or "").strip()
    if not evaluation_id or not selected_method:
        return error_response(
            "evaluation_id and selected_method are required.",
            status_code=400,
            details={
                "evaluation_id": "required" if not evaluation_id else "ok",
                "selected_method": "required" if not selected_method else "ok",
            },
        )

    try:
        selection = uniform_evaluation_repository.select_evaluation(evaluation_id, selected_method)
        return json_response(selection)
    except FileNotFoundError as exc:
        return error_response(str(exc), status_code=404)
    except ValueError as exc:
        return error_response(str(exc), status_code=400)
    except Exception as exc:
        return error_response(
            "Could not save selected uniform evaluation.",
            status_code=500,
            details={"error": str(exc)},
        )


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=config.PORT, debug=config.DEBUG, threaded=True)
