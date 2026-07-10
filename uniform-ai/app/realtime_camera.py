from __future__ import annotations

import json
import logging
import os
import time
from pathlib import Path
from threading import RLock
from typing import Any

import cv2
import numpy as np

from face.face_engine import FaceEngineError
from pose_estimation.face_matching import match_face_to_selected_pose
from pose_estimation.pose_regions import COCO_KEYPOINT_NAMES, bbox_area, normalize_bbox
from uniform_validation.labels import CANONICAL_CLASS_ID_TO_NAME


logger = logging.getLogger(__name__)

PIPELINE_NAME = "YOLOv8 Pose + InsightFace + YOLOv8 V2 Uniform"
UNKNOWN_LABEL = "Unknown person"
DEFAULT_FRAME_SIZE = 640
DEFAULT_UNIFORM_CONFIDENCE = 0.35
DEFAULT_POSE_CONFIDENCE = 0.25
KEYPOINT_SELECTION_CONFIDENCE = 0.2

COCO_SKELETON = [
    {"from": "left_shoulder", "to": "right_shoulder"},
    {"from": "left_shoulder", "to": "left_elbow"},
    {"from": "left_elbow", "to": "left_wrist"},
    {"from": "right_shoulder", "to": "right_elbow"},
    {"from": "right_elbow", "to": "right_wrist"},
    {"from": "left_shoulder", "to": "left_hip"},
    {"from": "right_shoulder", "to": "right_hip"},
    {"from": "left_hip", "to": "right_hip"},
    {"from": "left_hip", "to": "left_knee"},
    {"from": "left_knee", "to": "left_ankle"},
    {"from": "right_hip", "to": "right_knee"},
    {"from": "right_knee", "to": "right_ankle"},
    {"from": "nose", "to": "left_eye"},
    {"from": "nose", "to": "right_eye"},
    {"from": "left_eye", "to": "left_ear"},
    {"from": "right_eye", "to": "right_ear"},
]

_MODEL_LOCK = RLock()
_POSE_MODEL_CACHE: dict[str, Any] = {}
_UNIFORM_MODEL_CACHE: dict[str, Any] = {}
_UNIFORM_CLASS_NAMES_CACHE: dict[str, dict[int, str]] = {}


def analyze_realtime_camera_request(flask_request, config, face_engine, student_repository) -> tuple[dict[str, Any], int]:
    started = time.perf_counter()
    image, decode_error = _decode_request_image(flask_request)
    if decode_error is not None:
        return decode_error, 400

    frame_height, frame_width = image.shape[:2]
    uniform_confidence = _parse_float(
        _form_value(flask_request, "confidence_threshold", "confidence", "conf"),
        DEFAULT_UNIFORM_CONFIDENCE,
        minimum=0.0,
        maximum=1.0,
    )
    face_threshold = _parse_float(
        _form_value(flask_request, "face_threshold"),
        float(getattr(config, "FACE_SIMILARITY_THRESHOLD", 0.5)),
        minimum=0.0,
        maximum=1.0,
    )
    frame_size = _parse_int(
        _form_value(flask_request, "frame_size", "image_size", "imgsz"),
        int(getattr(config, "YOLO_IMAGE_SIZE", DEFAULT_FRAME_SIZE)),
        minimum=320,
        maximum=1280,
    )
    pose_confidence = _parse_float(
        _form_value(flask_request, "pose_confidence", "person_confidence"),
        float(getattr(config, "POSE_PERSON_CONFIDENCE", DEFAULT_POSE_CONFIDENCE)),
        minimum=0.0,
        maximum=1.0,
    )
    run_face = _parse_bool(_form_value(flask_request, "run_face"), default=True)
    run_uniform = _parse_bool(_form_value(flask_request, "run_uniform"), default=True)

    pose_path = _resolve_pose_model_path(flask_request, config)
    if pose_path is None:
        return (
            _base_response(
                success=False,
                message="YOLOv8 pose model is not available.",
                frame_width=frame_width,
                frame_height=frame_height,
                started=started,
            ),
            200,
        )

    uniform_path = _resolve_uniform_model_path(flask_request, config)
    if run_uniform and uniform_path is None:
        return (
            _base_response(
                success=False,
                message="YOLOv8 uniform model is not available.",
                frame_width=frame_width,
                frame_height=frame_height,
                started=started,
            ),
            200,
        )

    try:
        pose_model = _load_yolo_model(_POSE_MODEL_CACHE, pose_path, "pose")
        pose_result = _run_pose_model(
            pose_model=pose_model,
            image=image,
            confidence=pose_confidence,
            image_size=frame_size,
            config=config,
        )
        selected_person = pose_result.get("selected_person")
        if selected_person is None:
            return (
                _base_response(
                    success=True,
                    message="No person detected",
                    frame_width=frame_width,
                    frame_height=frame_height,
                    started=started,
                ),
                200,
            )

        face_payload, identity_payload = _unknown_face_identity()
        if run_face:
            face_payload, identity_payload = _identify_selected_face(
                image=image,
                selected_person=selected_person,
                config=config,
                face_engine=face_engine,
                student_repository=student_repository,
                threshold=face_threshold,
            )

        uniform_detections: list[dict[str, Any]] = []
        if run_uniform and uniform_path is not None:
            uniform_model = _load_yolo_model(_UNIFORM_MODEL_CACHE, uniform_path, "uniform")
            class_names = _uniform_class_names(uniform_path, uniform_model, config)
            uniform_detections = _run_uniform_model(
                uniform_model=uniform_model,
                image=image,
                class_names=class_names,
                confidence=uniform_confidence,
                image_size=frame_size,
                config=config,
            )

        return (
            {
                "success": True,
                "message": "OK",
                "frame_width": frame_width,
                "frame_height": frame_height,
                "processing_time_ms": _elapsed_ms(started),
                "selected_person": _public_person(selected_person),
                "face": face_payload,
                "identity": identity_payload,
                "uniform_detections": uniform_detections,
                "pipeline": PIPELINE_NAME,
            },
            200,
        )
    except ValueError as exc:
        return (
            _base_response(
                success=False,
                message=str(exc),
                frame_width=frame_width,
                frame_height=frame_height,
                started=started,
            ),
            400,
        )
    except Exception as exc:
        logger.exception("Real-time camera analysis failed")
        return (
            _base_response(
                success=False,
                message=f"Real-time camera analysis failed: {exc}",
                frame_width=frame_width,
                frame_height=frame_height,
                started=started,
            ),
            200,
        )


def _decode_request_image(flask_request) -> tuple[np.ndarray | None, dict[str, Any] | None]:
    upload = flask_request.files.get("image")
    if upload is None:
        return None, {"success": False, "message": "Missing image file. Use multipart field 'image'."}

    data = upload.read()
    if not data:
        return None, {"success": False, "message": "Empty image file."}

    try:
        buffer = np.frombuffer(data, dtype=np.uint8)
        image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
    except Exception:
        image = None

    if image is None:
        return None, {"success": False, "message": "Invalid image. Could not decode uploaded frame."}
    return image, None


def _run_pose_model(pose_model, image: np.ndarray, confidence: float, image_size: int, config) -> dict[str, Any]:
    with _MODEL_LOCK:
        results = pose_model.predict(
            source=image,
            conf=confidence,
            imgsz=image_size,
            device=_ultralytics_device(config),
            verbose=False,
        )

    people = _extract_pose_people(results[0] if results else None)
    selected = _select_closest_person(people)
    return {
        "people": people,
        "person_count": len(people),
        "selected_person": selected,
        "selected": selected is not None,
    }


def _extract_pose_people(result: Any) -> list[dict[str, Any]]:
    if result is None or result.boxes is None or len(result.boxes) == 0:
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
        bbox = _round_bbox(normalize_bbox([float(value) for value in bbox_values.tolist()]))
        person_confidence = float(box_conf[person_index]) if person_index < len(box_conf) else 0.0
        keypoints: list[dict[str, Any]] = []
        valid_keypoint_confidences: list[float] = []

        if keypoints_xy is not None and person_index < len(keypoints_xy):
            xy_values = keypoints_xy[person_index]
            if keypoints_conf is not None and person_index < len(keypoints_conf):
                confidence_values = keypoints_conf[person_index]
            else:
                confidence_values = np.ones(len(xy_values), dtype=np.float32)

            for keypoint_index, (xy, point_confidence) in enumerate(zip(xy_values, confidence_values)):
                x = float(xy[0])
                y = float(xy[1])
                confidence = float(point_confidence)
                visible = bool(confidence >= KEYPOINT_SELECTION_CONFIDENCE and x > 0.0 and y > 0.0)
                if visible:
                    valid_keypoint_confidences.append(confidence)
                keypoints.append(
                    {
                        "index": keypoint_index,
                        "name": COCO_KEYPOINT_NAMES[keypoint_index]
                        if keypoint_index < len(COCO_KEYPOINT_NAMES)
                        else f"keypoint_{keypoint_index}",
                        "x": round(x, 2),
                        "y": round(y, 2),
                        "confidence": round(confidence, 4),
                        "visible": visible,
                    }
                )

        keypoint_factor = min(1.0, len(valid_keypoint_confidences) / 8.0) if keypoints else 0.6
        selection_score = bbox_area(bbox) * max(0.0, person_confidence) * (0.35 + (0.65 * keypoint_factor))
        pose_confidence = (
            person_confidence + float(np.mean(valid_keypoint_confidences))
        ) / 2.0 if valid_keypoint_confidences else person_confidence
        people.append(
            {
                "index": person_index,
                "bbox": bbox,
                "bbox_xyxy": bbox,
                "pose_bbox": bbox,
                "confidence": round(person_confidence, 4),
                "person_confidence": round(person_confidence, 4),
                "pose_confidence": round(pose_confidence, 4),
                "keypoints": keypoints,
                "valid_keypoint_count": len(valid_keypoint_confidences),
                "selection_score": round(selection_score, 4),
            }
        )

    return people


def _select_closest_person(people: list[dict[str, Any]]) -> dict[str, Any] | None:
    candidates = [person for person in people if bbox_area(person.get("bbox", [0, 0, 0, 0])) > 0.0]
    if not candidates:
        return None
    selected = max(candidates, key=lambda item: float(item.get("selection_score", 0.0)))
    selected = dict(selected)
    selected["method"] = "largest_valid_pose_area"
    return selected


def _identify_selected_face(
    image: np.ndarray,
    selected_person: dict[str, Any],
    config,
    face_engine,
    student_repository,
    threshold: float,
) -> tuple[dict[str, Any] | None, dict[str, Any]]:
    try:
        detections = face_engine.extract_faces_from_bgr(image)
    except FaceEngineError as exc:
        logger.warning("InsightFace could not process real-time frame: %s", exc)
        return None, _unknown_identity()
    except Exception as exc:
        logger.warning("Unexpected face analysis error for real-time frame: %s", exc)
        return None, _unknown_identity()

    if not detections:
        return None, _unknown_identity()

    pose_face_match = match_face_to_selected_pose(detections, selected_person, image.shape, config)
    if not pose_face_match.get("matched"):
        return None, _unknown_identity()

    selected_detection = pose_face_match["selected_detection"]
    match = _best_embedding_match(selected_detection.embedding, face_engine, student_repository, threshold)
    face_confidence = match.get("confidence")
    if face_confidence is None:
        face_confidence = round(float(selected_detection.detection_score), 6)

    label = str(match.get("label") or UNKNOWN_LABEL)
    face_payload = {
        "bbox": _round_bbox(selected_detection.bbox),
        "confidence": face_confidence,
        "detection_score": round(float(selected_detection.detection_score), 6),
        "student_code": match.get("student_code"),
        "studentCode": match.get("student_code"),
        "student_id": match.get("student_id"),
        "studentId": match.get("student_id"),
        "face_data_id": match.get("face_data_id"),
        "faceDataId": match.get("face_data_id"),
        "id": match.get("id"),
        "code": match.get("student_code"),
        "label": label,
    }
    return face_payload, match


def _best_embedding_match(embedding, face_engine, student_repository, threshold: float) -> dict[str, Any]:
    best_record: dict[str, Any] | None = None
    best_sample: dict[str, Any] | None = None
    best_score: float | None = None

    try:
        stored_embeddings = student_repository.iter_embeddings()
    except Exception as exc:
        logger.warning("Could not read face embeddings for real-time frame: %s", exc)
        stored_embeddings = []

    for record, sample, stored_embedding in stored_embeddings:
        score = face_engine.cosine_similarity(embedding, stored_embedding)
        if best_score is None or score > best_score:
            best_score = score
            best_record = record
            best_sample = sample

    confidence = round(float(best_score), 6) if best_score is not None else None
    matched = bool(best_record is not None and best_score is not None and best_score >= threshold)
    if not matched or best_record is None:
        return _unknown_identity(confidence)

    student_code = str(best_record.get("student_id") or "").strip() or None
    student_name = str(best_record.get("student_name") or "").strip() or student_code
    sample_id = best_sample.get("sample_id") if best_sample else None
    return {
        "matched": True,
        "student_code": student_code,
        "studentCode": student_code,
        "student_id": student_code,
        "studentId": student_code,
        "face_data_id": student_code,
        "faceDataId": student_code,
        "id": student_code,
        "code": student_code,
        "label": student_code or student_name,
        "student_name": student_name,
        "studentName": student_name,
        "confidence": confidence,
        "similarity": confidence,
        "threshold": threshold,
        "best_sample_id": sample_id,
    }


def _run_uniform_model(
    uniform_model,
    image: np.ndarray,
    class_names: dict[int, str],
    confidence: float,
    image_size: int,
    config,
) -> list[dict[str, Any]]:
    with _MODEL_LOCK:
        results = uniform_model.predict(
            source=image,
            conf=confidence,
            imgsz=image_size,
            device=_ultralytics_device(config),
            verbose=False,
        )

    if not results:
        return []
    result = results[0]
    if result.boxes is None or len(result.boxes) == 0:
        return []

    detections: list[dict[str, Any]] = []
    for index, box in enumerate(result.boxes, start=1):
        class_id = int(box.cls.detach().cpu().item())
        score = float(box.conf.detach().cpu().item())
        if score < confidence:
            continue
        class_name = _class_name_for_id(class_id, class_names, getattr(result, "names", {}))
        if class_name.strip().lower() == "person":
            continue
        bbox = _round_bbox([float(value) for value in box.xyxy[0].detach().cpu().tolist()])
        detections.append(
            {
                "index": index,
                "class_id": class_id,
                "class_name": class_name,
                "className": class_name,
                "confidence": round(score, 4),
                "bbox": bbox,
                "bbox_xyxy": bbox,
                "x1": bbox[0],
                "y1": bbox[1],
                "x2": bbox[2],
                "y2": bbox[3],
            }
        )
    return detections


def _load_yolo_model(cache: dict[str, Any], path: Path, label: str):
    cache_key = str(path.resolve())
    with _MODEL_LOCK:
        model = cache.get(cache_key)
        if model is not None:
            return model

        from ultralytics import YOLO

        logger.info("Loading YOLOv8 %s model from %s", label, cache_key)
        model = YOLO(cache_key)
        cache[cache_key] = model
        logger.info("YOLOv8 %s model loaded", label)
        return model


def _uniform_class_names(model_path: Path, uniform_model, config) -> dict[int, str]:
    cache_key = str(model_path.resolve())
    cached = _UNIFORM_CLASS_NAMES_CACHE.get(cache_key)
    if cached is not None:
        return dict(cached)

    names: dict[int, str] | None = None
    for directory in _class_metadata_dirs(model_path, config):
        names = _load_class_metadata(directory)
        if names:
            break
    if not names:
        names = _normalize_names(getattr(uniform_model, "names", {}))

    if names != dict(CANONICAL_CLASS_ID_TO_NAME):
        raise ValueError(f"MODEL_CLASS_MAPPING_MISMATCH: real-time YOLO classes must be {CANONICAL_CLASS_ID_TO_NAME}. Got: {names}")

    _UNIFORM_CLASS_NAMES_CACHE[cache_key] = dict(names)
    return names


def _class_metadata_dirs(model_path: Path, config) -> list[Path]:
    dirs: list[Path] = []
    for candidate in [model_path.parent, getattr(config, "YOLO_DIR", None)]:
        if candidate is None:
            continue
        path = Path(candidate)
        if path not in dirs:
            dirs.append(path)
    return dirs


def _load_class_metadata(directory: Path) -> dict[int, str] | None:
    json_path = directory / "class_names.json"
    if json_path.exists():
        try:
            return _normalize_names(json.loads(json_path.read_text(encoding="utf-8")))
        except Exception as exc:
            logger.warning("Could not read class names from %s: %s", json_path, exc)

    txt_path = directory / "classes.txt"
    if txt_path.exists():
        try:
            lines = [line.strip() for line in txt_path.read_text(encoding="utf-8").splitlines()]
            return {index: name for index, name in enumerate(lines) if name}
        except Exception as exc:
            logger.warning("Could not read class names from %s: %s", txt_path, exc)
    return None


def _resolve_pose_model_path(flask_request, config) -> Path | None:
    candidates = [
        _form_value(flask_request, "yolov8_pose_model"),
        os.getenv("UNIFORM_AI_REALTIME_YOLOV8_POSE_MODEL"),
        getattr(config, "POSE_MODEL_PATH", None),
        "yolov8n-pose.pt",
        "YOLOv8/yolov8n-pose.pt",
        "yolov8s-pose.pt",
        "YOLOv8/yolov8-pose.pt",
        "YOLOv8/yolov8_pose.pt",
    ]
    return _first_existing_path(candidates, config)


def _resolve_uniform_model_path(flask_request, config) -> Path | None:
    candidates = [
        _form_value(flask_request, "yolov8_uniform_model"),
        os.getenv("UNIFORM_AI_REALTIME_YOLOV8_UNIFORM_MODEL"),
        getattr(config, "YOLOV8_V2_WEIGHTS_PATH", None),
        getattr(config, "YOLO_WEIGHTS_PATH", None),
        "yolov8_6class/best.pt",
    ]
    return _first_existing_path(candidates, config)


def _first_existing_path(candidates: list[Any], config) -> Path | None:
    for candidate in candidates:
        path = _existing_path(candidate, config)
        if path is not None:
            return path
    return None


def _existing_path(value: Any, config) -> Path | None:
    if value is None:
        return None
    raw = str(value).strip().strip("\"'")
    if not raw:
        return None

    base_dir = Path(getattr(config, "BASE_DIR", Path.cwd()))
    source = Path(raw).expanduser()
    candidates = [source] if source.is_absolute() else [base_dir / source, Path.cwd() / source]
    if not source.is_absolute() and len(source.parts) == 1:
        candidates.append(base_dir / "YOLOv8" / source)

    for candidate in candidates:
        try:
            resolved = candidate.resolve()
        except OSError:
            continue
        if resolved.exists() and resolved.is_file():
            return resolved
    return None


def _public_person(person: dict[str, Any]) -> dict[str, Any]:
    return {
        "bbox": _round_bbox(person.get("bbox", [])),
        "confidence": person.get("confidence"),
        "keypoints": [
            {
                "name": keypoint.get("name"),
                "x": keypoint.get("x"),
                "y": keypoint.get("y"),
                "confidence": keypoint.get("confidence"),
            }
            for keypoint in person.get("keypoints", [])
        ],
        "skeleton": COCO_SKELETON,
    }


def _unknown_face_identity() -> tuple[None, dict[str, Any]]:
    return None, _unknown_identity()


def _unknown_identity(confidence: float | None = None) -> dict[str, Any]:
    return {
        "matched": False,
        "student_code": None,
        "studentCode": None,
        "student_id": None,
        "studentId": None,
        "face_data_id": None,
        "faceDataId": None,
        "id": None,
        "code": None,
        "label": UNKNOWN_LABEL,
        "confidence": confidence,
    }


def _base_response(
    success: bool,
    message: str,
    frame_width: int | None,
    frame_height: int | None,
    started: float,
) -> dict[str, Any]:
    return {
        "success": success,
        "message": message,
        "frame_width": frame_width,
        "frame_height": frame_height,
        "processing_time_ms": _elapsed_ms(started),
        "selected_person": None,
        "face": None,
        "identity": _unknown_identity(),
        "uniform_detections": [],
        "pipeline": PIPELINE_NAME,
    }


def _form_value(flask_request, *names: str) -> str | None:
    for name in names:
        value = flask_request.form.get(name)
        if value is not None and str(value).strip() != "":
            return str(value).strip()
    return None


def _parse_bool(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text == "":
        return default
    return text in {"1", "true", "yes", "on"}


def _parse_float(value: Any, default: float, minimum: float, maximum: float) -> float:
    if value is None or str(value).strip() == "":
        return default
    try:
        parsed = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Expected numeric value, got {value!r}.") from exc
    if parsed < minimum or parsed > maximum:
        raise ValueError(f"Numeric value must be between {minimum} and {maximum}.")
    return parsed


def _parse_int(value: Any, default: int, minimum: int, maximum: int) -> int:
    if value is None or str(value).strip() == "":
        return default
    try:
        parsed = int(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"Expected integer value, got {value!r}.") from exc
    if parsed < minimum or parsed > maximum:
        raise ValueError(f"Integer value must be between {minimum} and {maximum}.")
    return parsed


def _normalize_names(names: Any) -> dict[int, str]:
    if isinstance(names, dict):
        return {int(class_id): str(name) for class_id, name in names.items()}
    if isinstance(names, (list, tuple)):
        return {index: str(name) for index, name in enumerate(names)}
    return {}


def _class_name_for_id(class_id: int, class_names: dict[int, str], model_names: Any) -> str:
    if class_id in class_names:
        return class_names[class_id]
    normalized_model_names = _normalize_names(model_names)
    return normalized_model_names.get(class_id, f"class_{class_id}")


def _round_bbox(values: Any) -> list[float]:
    bbox = [float(value) for value in list(values)[:4]]
    if len(bbox) < 4:
        return []
    return [round(value, 2) for value in bbox]


def _ultralytics_device(config) -> str:
    device = str(getattr(config, "DEVICE", "cpu"))
    if device.startswith("cuda"):
        return device.replace("cuda:", "")
    return "cpu"


def _elapsed_ms(started: float) -> int:
    return int(round(max(0.0, time.perf_counter() - started) * 1000))
