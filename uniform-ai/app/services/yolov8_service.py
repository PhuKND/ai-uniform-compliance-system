from __future__ import annotations

import ast
import json
from datetime import datetime
from pathlib import Path
from threading import Lock
from typing import Any

import cv2

from uniform_validation.labels import (
    ANNOTATION_COLORS_BGR,
    CANONICAL_CLASS_ID_TO_NAME,
    vietnamese_component_label,
)
from utils.annotation_utils import draw_unicode_label
from utils.output_images import read_bgr_image, save_bgr_jpeg


class YoloV8Service:
    OFFICIAL_CLASS_NAMES = dict(CANONICAL_CLASS_ID_TO_NAME)
    SOURCE_DETECTOR = "yolov8_v2"

    BOX_COLORS_BGR = {
        class_id: ANNOTATION_COLORS_BGR[class_name]
        for class_id, class_name in OFFICIAL_CLASS_NAMES.items()
    }

    def __init__(self, config) -> None:
        self.config = config
        self.yolo_dir = Path(config.YOLO_DIR)
        self.output_dir = Path(config.YOLO_OUTPUT_DIR)
        self.default_confidence = float(config.YOLO_CONFIDENCE)
        self.default_image_size = int(config.YOLO_IMAGE_SIZE)
        self.default_max_det = int(getattr(config, "YOLO_MAX_DET", 30))
        self.default_save_annotated = bool(config.YOLO_SAVE_ANNOTATED)
        self.model_id = str(getattr(config, "YOLOV8_V2_MODEL_ID", "yolov8s_uniform_6class_20260630"))

        self._model = None
        self._weights_path: Path | None = None
        self._class_names: dict[int, str] | None = None
        self._metadata: dict[str, Any] | None = None
        self._load_error: str | None = None
        self._lock = Lock()

    @property
    def class_names(self) -> dict[int, str]:
        return dict(self._class_names or self.OFFICIAL_CLASS_NAMES)

    @property
    def weights_path(self) -> Path | None:
        return self._weights_path

    @staticmethod
    def _normalize_names(names: Any) -> dict[int, str]:
        if isinstance(names, dict):
            return {int(class_id): str(name) for class_id, name in names.items()}
        if isinstance(names, (list, tuple)):
            return {index: str(name) for index, name in enumerate(names)}
        raise ValueError(f"Unsupported class names format: {type(names).__name__}")

    @classmethod
    def _verify_class_names(cls, source_name: str, names: dict[int, str]) -> None:
        if names != cls.OFFICIAL_CLASS_NAMES:
            raise RuntimeError(
                "MODEL_CLASS_MAPPING_MISMATCH: "
                f"{source_name} must match the official 6-class schema exactly. Got: {names}"
            )

    def _load_names_from_json(self, path: Path) -> dict[int, str]:
        return self._normalize_names(json.loads(path.read_text(encoding="utf-8")))

    def _load_names_from_txt(self, path: Path) -> dict[int, str]:
        lines = [line.strip() for line in path.read_text(encoding="utf-8").splitlines()]
        return {index: name for index, name in enumerate(lines) if name}

    def _load_names_from_data_yaml(self, path: Path) -> dict[int, str]:
        names: dict[int, str] = {}
        list_names: list[str] = []
        in_names_block = False

        for raw_line in path.read_text(encoding="utf-8").splitlines():
            stripped = raw_line.strip()
            if not stripped or stripped.startswith("#"):
                continue

            if stripped.startswith("names:"):
                in_names_block = True
                inline_value = stripped.split(":", 1)[1].strip()
                if inline_value:
                    parsed = ast.literal_eval(inline_value)
                    return self._normalize_names(parsed)
                continue

            if in_names_block and not raw_line.startswith((" ", "\t", "-")):
                break

            if in_names_block and stripped.startswith("-"):
                list_names.append(stripped[1:].strip().strip("'\""))
                continue

            if in_names_block and ":" in stripped:
                class_id, class_name = stripped.split(":", 1)
                if class_id.strip().isdigit():
                    names[int(class_id.strip())] = class_name.strip().strip("'\"")

        return {index: name for index, name in enumerate(list_names)} if list_names else names

    def _load_verified_class_names(self) -> dict[int, str]:
        loaders = {
            "class_names.json": self._load_names_from_json,
            "classes.txt": self._load_names_from_txt,
            "data.yaml": self._load_names_from_data_yaml,
        }

        verified: dict[int, str] | None = None
        for filename, loader in loaders.items():
            path = self.yolo_dir / filename
            if not path.exists():
                raise FileNotFoundError(f"YOLOv8 V2 metadata file not found: {path}")
            names = loader(path)
            self._verify_class_names(filename, names)
            verified = names

        assert verified is not None
        return verified

    def _load_metadata(self) -> dict[str, Any]:
        metadata: dict[str, Any] = {
            "model_id": self.model_id,
            "source_detector": self.SOURCE_DETECTOR,
            "class_names": {str(key): value for key, value in self.OFFICIAL_CLASS_NAMES.items()},
        }
        for filename in ["model_provenance.json", "validation_metrics.json", "training_configuration.json"]:
            path = self.yolo_dir / filename
            if not path.exists():
                continue
            try:
                metadata[Path(filename).stem] = json.loads(path.read_text(encoding="utf-8"))
            except Exception as exc:
                metadata[f"{Path(filename).stem}_error"] = str(exc)
        return metadata

    def _resolve_weights_path(self) -> Path:
        if not bool(getattr(self.config, "YOLOV8_V2_ENABLED", True)):
            raise RuntimeError("YOLOV8_V2_DISABLED: YOLOv8 V2 is disabled by configuration.")

        preferred = Path(self.config.YOLOV8_V2_WEIGHTS_PATH).resolve()
        package_dir = self.yolo_dir.resolve()
        if preferred.exists() and preferred.is_file():
            if preferred.name.lower() != "best.pt":
                raise RuntimeError(
                    "YOLOV8_V2_INVALID_WEIGHTS: production inference must use yolov8_6class/best.pt."
                )
            if preferred.parent != package_dir:
                raise RuntimeError(
                    "YOLOV8_V2_INVALID_WEIGHTS: best.pt must be loaded from the configured yolov8_6class package."
                )
            return preferred
        raise FileNotFoundError("YOLOV8_V2_MODEL_NOT_FOUND: yolov8_6class/best.pt was not found.")

    def _ultralytics_device(self) -> str:
        configured = str(getattr(self.config, "YOLOV8_V2_DEVICE", "auto") or "auto").strip().lower()
        if configured and configured != "auto":
            return configured.replace("cuda:", "")
        device = str(self.config.DEVICE)
        if device.startswith("cuda"):
            return device.replace("cuda:", "")
        return "cpu"

    def _load(self) -> None:
        if self._model is not None:
            return

        try:
            from ultralytics import YOLO

            self._class_names = self._load_verified_class_names()
            self._weights_path = self._resolve_weights_path()
            self._model = YOLO(str(self._weights_path))
            model_names = self._normalize_names(getattr(self._model, "names", {}))
            if bool(getattr(self.config, "YOLOV8_V2_STRICT_CLASS_MAPPING", True)):
                self._verify_class_names("model.names", model_names)
            self._metadata = self._load_metadata()
            self._load_error = None
        except Exception as exc:
            self._model = None
            self._load_error = str(exc)
            raise

    @staticmethod
    def parse_bool(value: str | bool | None, default: bool = True) -> bool:
        if value is None or value == "":
            return default
        if isinstance(value, bool):
            return value
        return str(value).strip().lower() in {"1", "true", "yes", "on"}

    def parse_confidence(self, value: str | float | None) -> float:
        if value in (None, ""):
            confidence = self.default_confidence
        else:
            try:
                confidence = float(value)
            except (TypeError, ValueError) as exc:
                raise ValueError("YOLOv8 V2 confidence must be a number.") from exc
        if not 0.0 <= confidence <= 1.0:
            raise ValueError("YOLOv8 V2 confidence must be between 0.0 and 1.0.")
        return confidence

    def parse_image_size(self, value: str | int | None) -> int:
        if value in (None, ""):
            image_size = self.default_image_size
        else:
            try:
                image_size = int(value)
            except (TypeError, ValueError) as exc:
                raise ValueError("YOLOv8 V2 image_size must be an integer.") from exc
        if not 320 <= image_size <= 1280:
            raise ValueError("YOLOv8 V2 image_size must be between 320 and 1280.")
        return image_size

    def parse_max_det(self, value: str | int | None) -> int:
        if value in (None, ""):
            max_det = self.default_max_det
        else:
            try:
                max_det = int(value)
            except (TypeError, ValueError) as exc:
                raise ValueError("YOLOv8 V2 max_det must be an integer.") from exc
        if not 1 <= max_det <= 300:
            raise ValueError("YOLOv8 V2 max_det must be between 1 and 300.")
        return max_det

    def _extract_detections(self, result: Any) -> list[dict[str, Any]]:
        detections: list[dict[str, Any]] = []
        if result.boxes is None or len(result.boxes) == 0:
            return detections

        for index, box in enumerate(result.boxes, start=1):
            class_id = int(box.cls.detach().cpu().item())
            confidence = float(box.conf.detach().cpu().item())
            xyxy = [float(value) for value in box.xyxy[0].detach().cpu().tolist()]

            if class_id not in self.OFFICIAL_CLASS_NAMES:
                raise RuntimeError(f"MODEL_CLASS_MAPPING_MISMATCH: YOLOv8 V2 returned unsupported class id {class_id}.")

            x1, y1, x2, y2 = xyxy
            bbox_area = max(0.0, x2 - x1) * max(0.0, y2 - y1)
            class_name = self.OFFICIAL_CLASS_NAMES[class_id]
            bbox = [round(value, 2) for value in xyxy]
            detections.append(
                {
                    "detection_id": f"{self.SOURCE_DETECTOR}_{index:04d}",
                    "index": index,
                    "selection_rank": index,
                    "class_id": class_id,
                    "class_name": class_name,
                    "confidence": round(confidence, 4),
                    "bbox": bbox,
                    "bbox_xyxy": bbox,
                    "xyxy": bbox,
                    "bbox_area": round(float(bbox_area), 2),
                    "x1": bbox[0],
                    "y1": bbox[1],
                    "x2": bbox[2],
                    "y2": bbox[3],
                    "source_detector": self.SOURCE_DETECTOR,
                    "model_version": self.model_id,
                    "pose_validation": None,
                    "accepted": None,
                    "rejection_reason": None,
                }
            )

        return detections

    def _save_annotated_image(self, image_path: Path, detections: list[dict[str, Any]]) -> Path:
        image = read_bgr_image(image_path)

        image_height, image_width = image.shape[:2]
        line_thickness = max(2, round(min(image_height, image_width) / 320))
        font_size = max(16, min(30, int(round(min(image_height, image_width) / 32))))
        used_label_rects: list[tuple[int, int, int, int]] = []

        for detection in detections:
            class_id = int(detection["class_id"])
            x1, y1, x2, y2 = [int(round(value)) for value in detection["bbox_xyxy"]]
            color = self.BOX_COLORS_BGR.get(class_id, (255, 255, 255))
            label = vietnamese_component_label(detection["class_name"])

            cv2.rectangle(image, (x1, y1), (x2, y2), color, thickness=line_thickness)
            draw_unicode_label(image, label, (x1, y1, x2, y2), color, font_size, used_label_rects)

        self.output_dir.mkdir(parents=True, exist_ok=True)
        filename = f"{image_path.stem}_yolov8_v2_{datetime.utcnow().strftime('%Y%m%d_%H%M%S_%f')}.jpg"
        output_path = self.output_dir / filename
        return save_bgr_jpeg(image, output_path)

    def _summary(self, detections: list[dict[str, Any]]) -> dict[str, Any]:
        detected_classes = sorted(
            {detection["class_name"] for detection in detections},
            key=lambda name: list(self.OFFICIAL_CLASS_NAMES.values()).index(name),
        )
        return {
            "total_detections": len(detections),
            "detected_classes": detected_classes,
        }

    def predict(
        self,
        image_path: Path,
        confidence: str | float | None = None,
        image_size: str | int | None = None,
        save_annotated: str | bool | None = None,
        max_det: str | int | None = None,
    ) -> dict[str, Any]:
        confidence_value = self.parse_confidence(confidence)
        image_size_value = self.parse_image_size(image_size)
        max_det_value = self.parse_max_det(max_det)
        save_annotated_value = self.parse_bool(save_annotated, default=self.default_save_annotated)

        self._load()
        assert self._model is not None

        with self._lock:
            results = self._model.predict(
                source=str(image_path),
                conf=confidence_value,
                imgsz=image_size_value,
                max_det=max_det_value,
                device=self._ultralytics_device(),
                save=False,
                verbose=False,
            )

        result = results[0]
        detections = self._extract_detections(result)
        annotated_path = self._save_annotated_image(image_path, detections) if save_annotated_value else None

        return {
            "success": True,
            "model": "yolov8_v2",
            "model_id": self.model_id,
            "model_version": self.model_id,
            "source_detector": self.SOURCE_DETECTOR,
            "weights": self._weights_path.name if self._weights_path else None,
            "weights_path": str(self._weights_path) if self._weights_path else None,
            "class_mapping": {str(key): value for key, value in self.OFFICIAL_CLASS_NAMES.items()},
            "confidence_threshold": confidence_value,
            "image_size": image_size_value,
            "max_det": max_det_value,
            "detections": detections,
            "raw_yolo_detections": detections,
            "summary": self._summary(detections),
            "model_metadata": self._metadata or self._load_metadata(),
            "annotated_path": str(annotated_path) if annotated_path else None,
            "annotated_filename": annotated_path.name if annotated_path else None,
        }

    def status(self) -> dict[str, Any]:
        try:
            self._load()
            assert self._weights_path is not None
            return {
                "available": True,
                "model": "yolov8_v2",
                "model_id": self.model_id,
                "weights": str(self._weights_path),
                "weights_name": self._weights_path.name,
                "classes": {str(key): value for key, value in self.OFFICIAL_CLASS_NAMES.items()},
                "fallback_enabled": False,
                "last_pt_production_fallback": False,
            }
        except Exception as exc:
            return {
                "available": False,
                "error": str(exc),
                "classes": {str(key): value for key, value in self.OFFICIAL_CLASS_NAMES.items()},
                "fallback_enabled": False,
                "last_pt_production_fallback": False,
            }
