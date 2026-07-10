from __future__ import annotations

from pathlib import Path
from threading import Lock
from typing import Any

import cv2
import numpy as np

from .person_selector import select_closest_person
from .pose_regions import COCO_KEYPOINT_NAMES, bbox_area, bbox_diagonal, normalize_bbox


class PoseEstimationError(Exception):
    """Raised when the pose estimator cannot run or returns unusable output."""


class PoseEstimator:
    """YOLO pose wrapper for multi-person keypoints and one target selection."""

    def __init__(self, config) -> None:
        self.config = config
        self.model_path = str(self._cfg("POSE_MODEL_PATH", "yolov8n-pose.pt"))
        self.image_size = int(self._cfg("POSE_IMAGE_SIZE", 640))
        self.person_confidence = float(self._cfg("POSE_PERSON_CONFIDENCE", 0.25))
        self.min_keypoint_confidence = float(self._cfg("POSE_MIN_CONFIDENCE", 0.4))
        self.min_valid_keypoints = int(self._cfg("POSE_MIN_VALID_KEYPOINTS", 5))
        self._model = None
        self._load_error: str | None = None
        self._lock = Lock()

    @property
    def is_loaded(self) -> bool:
        return self._model is not None

    def _cfg(self, name: str, default: Any) -> Any:
        if isinstance(self.config, dict):
            return self.config.get(name, default)
        getter = getattr(self.config, "get", None)
        if callable(getter):
            return getter(name, default)
        return getattr(self.config, name, default)

    def _resolve_model_source(self) -> str:
        raw = self.model_path.strip() or "yolov8n-pose.pt"
        has_path_marker = any(marker in raw for marker in ["/", "\\", ":"])
        if not has_path_marker:
            return raw

        path = Path(raw).expanduser()
        if not path.is_absolute():
            base_dir = Path(self._cfg("BASE_DIR", Path.cwd()))
            path = base_dir / path
        return str(path.resolve())

    def _ultralytics_device(self) -> str:
        device = str(self._cfg("DEVICE", "cpu"))
        if device.startswith("cuda"):
            return device.replace("cuda:", "")
        return "cpu"

    def _load(self) -> None:
        if self._model is not None:
            return

        try:
            from ultralytics import YOLO

            self._model = YOLO(self._resolve_model_source())
            self._load_error = None
        except Exception as exc:
            self._model = None
            self._load_error = str(exc)
            raise PoseEstimationError(f"Pose estimation model could not be loaded: {exc}") from exc

    @staticmethod
    def _read_image_shape(image_path: Path) -> tuple[int, int, int]:
        path = Path(image_path)
        if not path.exists():
            raise PoseEstimationError(f"Image file does not exist: {path}")

        buffer = np.fromfile(str(path), dtype=np.uint8)
        image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
        if image is None:
            raise PoseEstimationError(f"Could not decode image for pose estimation: {path}")
        return image.shape

    def _extract_people(self, result: Any) -> list[dict[str, Any]]:
        if result.boxes is None or len(result.boxes) == 0:
            return []

        boxes_xyxy = result.boxes.xyxy.detach().cpu().numpy()
        box_conf = result.boxes.conf.detach().cpu().numpy() if result.boxes.conf is not None else np.ones(len(boxes_xyxy))

        keypoints_xy = None
        keypoints_conf = None
        if getattr(result, "keypoints", None) is not None:
            if result.keypoints.xy is not None:
                keypoints_xy = result.keypoints.xy.detach().cpu().numpy()
            if getattr(result.keypoints, "conf", None) is not None and result.keypoints.conf is not None:
                keypoints_conf = result.keypoints.conf.detach().cpu().numpy()

        people: list[dict[str, Any]] = []
        for person_index, bbox_values in enumerate(boxes_xyxy):
            bbox = [round(float(value), 2) for value in normalize_bbox([float(v) for v in bbox_values.tolist()])]
            keypoints: list[dict[str, Any]] = []
            valid_confidences: list[float] = []

            if keypoints_xy is not None and person_index < len(keypoints_xy):
                xy_values = keypoints_xy[person_index]
                if keypoints_conf is not None and person_index < len(keypoints_conf):
                    conf_values = keypoints_conf[person_index]
                else:
                    conf_values = np.ones(len(xy_values), dtype=np.float32)

                for keypoint_index, (xy, confidence) in enumerate(zip(xy_values, conf_values)):
                    x = float(xy[0])
                    y = float(xy[1])
                    point_confidence = float(confidence)
                    visible = bool(point_confidence >= self.min_keypoint_confidence and x > 0.0 and y > 0.0)
                    if visible:
                        valid_confidences.append(point_confidence)
                    keypoints.append(
                        {
                            "index": keypoint_index,
                            "name": COCO_KEYPOINT_NAMES[keypoint_index]
                            if keypoint_index < len(COCO_KEYPOINT_NAMES)
                            else f"keypoint_{keypoint_index}",
                            "x": round(x, 2),
                            "y": round(y, 2),
                            "confidence": round(point_confidence, 4),
                            "visible": visible,
                        }
                    )

            person_box_confidence = float(box_conf[person_index]) if person_index < len(box_conf) else 0.0
            keypoint_mean = float(np.mean(valid_confidences)) if valid_confidences else person_box_confidence
            pose_confidence = (person_box_confidence + keypoint_mean) / 2.0

            people.append(
                {
                    "index": person_index,
                    "pose_bbox": bbox,
                    "bbox_xyxy": bbox,
                    "bbox": bbox,
                    "pose_bbox_area": round(bbox_area(bbox), 2),
                    "pose_bbox_diagonal": round(bbox_diagonal(bbox), 2),
                    "person_confidence": round(person_box_confidence, 4),
                    "pose_confidence": round(float(pose_confidence), 4),
                    "keypoints": keypoints,
                    "valid_keypoint_count": len(valid_confidences),
                }
            )

        return people

    def estimate(self, image_path: Path) -> dict[str, Any]:
        image_path = Path(image_path)
        image_shape = self._read_image_shape(image_path)
        self._load()
        assert self._model is not None

        try:
            with self._lock:
                results = self._model.predict(
                    source=str(image_path),
                    conf=self.person_confidence,
                    imgsz=self.image_size,
                    device=self._ultralytics_device(),
                    verbose=False,
                )
        except Exception as exc:
            raise PoseEstimationError(f"Pose estimation inference failed: {exc}") from exc

        if not results:
            people: list[dict[str, Any]] = []
        else:
            people = self._extract_people(results[0])

        selected = select_closest_person(people, min_valid_keypoints=self.min_valid_keypoints)
        reason = None
        if selected is None:
            if not people:
                reason = "No person pose detected"
            else:
                reason = "Detected people did not have enough confident body keypoints"

        return {
            "available": True,
            "model": "yolov8-pose",
            "model_path": self.model_path,
            "method": "largest_pose_area",
            "image_shape": [int(image_shape[0]), int(image_shape[1]), int(image_shape[2])],
            "people": people,
            "person_count": len(people),
            "selected_person": selected,
            "selected_person_index": selected.get("index") if selected else None,
            "selected": selected is not None,
            "reason": reason,
            "thresholds": {
                "person_confidence": self.person_confidence,
                "min_keypoint_confidence": self.min_keypoint_confidence,
                "min_valid_keypoints": self.min_valid_keypoints,
            },
        }

    def status(self) -> dict[str, Any]:
        try:
            import ultralytics  # noqa: F401

            return {
                "available": True,
                "model": "yolov8-pose",
                "model_path": self.model_path,
                "model_loaded": self.is_loaded,
                "keypoint_schema": "COCO-17",
                "last_error": self._load_error,
            }
        except Exception as exc:
            return {
                "available": False,
                "model": "yolov8-pose",
                "model_path": self.model_path,
                "model_loaded": self.is_loaded,
                "error": str(exc),
                "last_error": self._load_error,
            }
