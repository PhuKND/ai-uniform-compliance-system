"""Integrated face recognition API routes."""

from __future__ import annotations

from pathlib import Path
from typing import Any

from flask import Blueprint, current_app, request
import cv2
import numpy as np

from .face_engine import (
    FaceEngine,
    FaceEngineError,
    ImageDecodeError,
    ModelInitializationError,
    MultipleFacesDetectedError,
    NoFaceDetectedError,
)
from .file_utils import (
    FileUploadError,
    InvalidFileTypeError,
    NoFileUploadedError,
    save_uploaded_image,
)
from .response_utils import ApiError, success_response
from .student_repository import (
    DuplicateStudentError,
    EmbeddingStorageError,
    InvalidStudentIdError,
    MetadataStorageError,
    RepositoryError,
    StudentNotFoundError,
    StudentRepository,
    normalize_student_id,
)
from pose_estimation import PoseEstimationError
from pose_estimation.face_matching import match_face_to_selected_pose


face_bp = Blueprint("face", __name__)


def _face_engine() -> FaceEngine:
    return current_app.extensions["face_engine"]


def _repository() -> StudentRepository:
    return current_app.extensions["student_repository"]


def _threshold() -> float:
    return float(current_app.config["FACE_SIMILARITY_THRESHOLD"])


def _parse_bool(value: str | bool | None, default: bool = True) -> bool:
    if value is None or value == "":
        return default
    if isinstance(value, bool):
        return value
    return str(value).strip().lower() in {"1", "true", "yes", "on"}


def _pose_estimator():
    return current_app.extensions.get("pose_estimator")


def _get_image_file():
    return request.files.get("image") or request.files.get("file")


def _save_request_image(purpose: str) -> Path:
    try:
        return save_uploaded_image(
            uploaded_file=_get_image_file(),
            upload_dir=current_app.config["FACE_UPLOAD_DIR"],
            purpose=purpose,
            allowed_extensions=current_app.config["FACE_ALLOWED_IMAGE_EXTENSIONS"],
        )
    except NoFileUploadedError as exc:
        raise ApiError(str(exc), 400, "NO_FILE_UPLOADED") from exc
    except InvalidFileTypeError as exc:
        raise ApiError(str(exc), 400, "INVALID_FILE_TYPE") from exc
    except FileUploadError as exc:
        raise ApiError(str(exc), 400, "FILE_UPLOAD_ERROR") from exc


def _required_form_value(name: str) -> str:
    value = (request.form.get(name) or "").strip()
    if value == "":
        raise ApiError(f"{name} is required", 400, "VALIDATION_ERROR", {name: "required"})
    return value


def _student_response(record: dict[str, Any] | None) -> dict[str, Any] | None:
    if record is None:
        return None

    samples = record.get("samples", [])
    return {
        "student_id": record.get("student_id"),
        "student_name": record.get("student_name"),
        "created_at": record.get("created_at"),
        "updated_at": record.get("updated_at"),
        "sample_count": len(samples) if isinstance(samples, list) else 0,
    }


def _sample_response(sample: dict[str, Any] | None) -> dict[str, Any] | None:
    if sample is None:
        return None

    response = {
        "sample_id": sample.get("sample_id"),
        "sample_label": sample.get("sample_label"),
        "created_at": sample.get("created_at"),
        "source_image": sample.get("source_image"),
        "face": sample.get("face"),
    }
    return {key: value for key, value in response.items() if value is not None}


def _handle_face_error(exc: FaceEngineError) -> None:
    if isinstance(exc, NoFaceDetectedError):
        raise ApiError(str(exc), 422, "NO_FACE_FOUND") from exc
    if isinstance(exc, MultipleFacesDetectedError):
        raise ApiError(str(exc), 422, "MULTIPLE_FACES_FOUND") from exc
    if isinstance(exc, ImageDecodeError):
        raise ApiError(str(exc), 400, "INVALID_IMAGE") from exc
    if isinstance(exc, ModelInitializationError):
        raise ApiError(str(exc), 500, "MODEL_INITIALIZATION_FAILED") from exc
    raise ApiError(str(exc), 500, "FACE_MODEL_FAILURE") from exc


def _read_image_shape(image_path: Path) -> tuple[int, int, int]:
    buffer = np.fromfile(str(image_path), dtype=np.uint8)
    image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
    if image is None:
        raise ApiError("Could not decode uploaded image", 400, "INVALID_IMAGE")
    return image.shape


def _pose_failure_payload(image_path: Path, reason: str) -> dict[str, Any]:
    image_shape = _read_image_shape(image_path)
    return {
        "available": False,
        "model": "yolov8-pose",
        "method": "largest_pose_area",
        "image_shape": [int(image_shape[0]), int(image_shape[1]), int(image_shape[2])],
        "people": [],
        "person_count": 0,
        "selected_person": None,
        "selected_person_index": None,
        "selected": False,
        "reason": reason,
    }


def _estimate_pose_result(image_path: Path, pose_result: dict[str, Any] | None = None) -> dict[str, Any]:
    if pose_result is not None:
        return pose_result

    estimator = _pose_estimator()
    if estimator is None:
        return _pose_failure_payload(image_path, "Pose estimator is not configured for the face service")

    try:
        return estimator.estimate(image_path)
    except PoseEstimationError as exc:
        return _pose_failure_payload(image_path, str(exc))


def _identity_from_match(match: dict[str, Any] | None) -> str | None:
    if not match or not match.get("matched"):
        return None
    student = match.get("student") or {}
    return student.get("student_id") or student.get("student_name")


def _no_pose_face_match_response(
    image_path: Path,
    pose_result: dict[str, Any],
    reason: str,
    detections: list[dict[str, Any]] | None = None,
    mode: str = "identify",
) -> dict[str, Any]:
    return {
        "image_saved_as": str(image_path),
        "mode": mode,
        "face_count": len(detections or []),
        "evaluated_face_count": 0,
        "ignored_face_count": len(detections or []),
        "face_matched_to_selected_pose": False,
        "identity": None,
        "confidence": None,
        "face_bbox": None,
        "best_match": None,
        "detections": [],
        "face_candidates": detections or [],
        "pose": {
            "available": bool(pose_result.get("available", False)),
            "method": pose_result.get("method", "largest_pose_area"),
            "person_count": int(pose_result.get("person_count", 0)),
            "selected": bool(pose_result.get("selected", False)),
            "selected_person": pose_result.get("selected_person"),
            "reason": pose_result.get("reason"),
        },
        "reason": reason,
        "storage_warnings": _repository().skipped_embeddings,
    }


def _best_match(embedding) -> dict[str, Any]:
    repo = _repository()
    engine = _face_engine()
    threshold = _threshold()

    best_record: dict[str, Any] | None = None
    best_sample: dict[str, Any] | None = None
    best_score: float | None = None

    for record, sample, stored_embedding in repo.iter_embeddings():
        score = engine.cosine_similarity(embedding, stored_embedding)
        if best_score is None or score > best_score:
            best_score = score
            best_record = record
            best_sample = sample

    matched = best_record is not None and best_score is not None and best_score >= threshold
    return {
        "matched": matched,
        "is_unknown": not matched,
        "similarity": round(best_score, 6) if best_score is not None else None,
        "threshold": threshold,
        "student": _student_response(best_record) if matched and best_record else None,
        "best_sample": _sample_response(best_sample),
        "best_sample_id": best_sample.get("sample_id") if best_sample else None,
        "best_sample_similarity": round(best_score, 6) if best_score is not None else None,
    }


def _best_student_sample_match(student_id: str, embedding) -> dict[str, Any]:
    repo = _repository()
    engine = _face_engine()
    threshold = _threshold()

    student = repo.get_student(student_id)
    loaded_samples = repo.get_loaded_samples(student_id)
    if not loaded_samples:
        raise EmbeddingStorageError(f"No valid embeddings loaded for student_id '{student_id}'")

    best_sample: dict[str, Any] | None = None
    best_score: float | None = None
    for sample, stored_embedding in loaded_samples:
        score = engine.cosine_similarity(embedding, stored_embedding)
        if best_score is None or score > best_score:
            best_score = score
            best_sample = sample

    verified = best_score is not None and best_score >= threshold
    student_payload = _student_response(student) or {}
    return {
        "matched": verified,
        "similarity": round(best_score, 6) if best_score is not None else None,
        "threshold": threshold,
        "student": student_payload,
        "sample_count": student_payload.get("sample_count", 0),
        "loaded_sample_count": len(loaded_samples),
        "best_sample": _sample_response(best_sample),
        "best_sample_id": best_sample.get("sample_id") if best_sample else None,
        "best_sample_similarity": round(best_score, 6) if best_score is not None else None,
    }


def face_health_data() -> dict[str, Any]:
    """Return service health without forcing InsightFace model initialization."""

    repo = _repository()
    engine = _face_engine()
    return {
        "status": "ok",
        "service": "face-recognition",
        "model_name": current_app.config["INSIGHTFACE_MODEL_NAME"],
        "model_loaded": engine.is_loaded,
        "providers": current_app.config["INSIGHTFACE_PROVIDERS"],
        "ctx_id": current_app.config["INSIGHTFACE_CTX_ID"],
        "similarity_threshold": _threshold(),
        "student_count": repo.student_count,
        "total_sample_count": repo.total_sample_count,
        "loaded_embeddings": repo.loaded_embedding_count,
        "skipped_embeddings": repo.skipped_embeddings,
        "storage": {
            "metadata_file": str(current_app.config["FACE_METADATA_FILE"]),
            "embedding_dir": str(current_app.config["FACE_EMBEDDING_DIR"]),
            "upload_dir": str(current_app.config["FACE_UPLOAD_DIR"]),
        },
        "pose_linking": {
            "enabled_by_default": True,
            "estimator_available": _pose_estimator() is not None,
        },
        "storage_migration": current_app.extensions.get("face_storage_migration", {}),
    }


def identify_image(image_path: Path) -> dict[str, Any]:
    """Identify every face found in an image path."""

    try:
        detections = _face_engine().extract_faces(image_path)
    except FaceEngineError as exc:
        _handle_face_error(exc)

    if not detections:
        raise ApiError("No face found in the uploaded image", 422, "NO_FACE_FOUND")

    response_detections = []
    for detection in detections:
        response_detections.append(
            {
                **detection.to_response(),
                "match": _best_match(detection.embedding),
            }
        )

    return {
        "image_saved_as": str(image_path),
        "face_count": len(response_detections),
        "best_match": response_detections[0]["match"] if len(response_detections) == 1 else None,
        "detections": response_detections,
        "storage_warnings": _repository().skipped_embeddings,
    }


def identify_image_for_selected_pose(image_path: Path, pose_result: dict[str, Any] | None = None) -> dict[str, Any]:
    """Identify only the face matched to the selected closest-person pose."""

    pose_result = _estimate_pose_result(image_path, pose_result)
    selected_person = pose_result.get("selected_person")

    try:
        detections = _face_engine().extract_faces(image_path)
    except FaceEngineError as exc:
        _handle_face_error(exc)

    face_candidates = [detection.to_response() for detection in detections]
    image_shape = tuple(pose_result.get("image_shape") or _read_image_shape(image_path))
    pose_face_match = match_face_to_selected_pose(detections, selected_person, image_shape, current_app.config)
    if not pose_face_match.get("matched"):
        return _no_pose_face_match_response(
            image_path,
            pose_result,
            pose_face_match.get("reason") or "No detected face matches the selected closest person pose",
            detections=face_candidates,
            mode="identify",
        ) | {
            "pose_face_match": {
                "candidates": pose_face_match.get("candidates", []),
                "best_candidate": pose_face_match.get("best_candidate"),
                "regions": pose_face_match.get("regions"),
            }
        }

    selected_detection = pose_face_match["selected_detection"]
    recognition_match = _best_match(selected_detection.embedding)
    selected_response = {
        **selected_detection.to_response(),
        "pose_match": pose_face_match.get("best_candidate"),
        "match": recognition_match,
    }
    identity = _identity_from_match(recognition_match)
    reason = (
        "Selected face matched pose and identity threshold"
        if identity
        else "Selected face matched pose, but no enrolled identity exceeded threshold"
    )

    return {
        "image_saved_as": str(image_path),
        "mode": "identify",
        "face_count": len(detections),
        "evaluated_face_count": 1,
        "ignored_face_count": max(0, len(detections) - 1),
        "face_matched_to_selected_pose": True,
        "identity": identity,
        "confidence": recognition_match.get("similarity"),
        "face_bbox": selected_detection.bbox,
        "best_match": recognition_match,
        "detections": [selected_response],
        "face_candidates": face_candidates,
        "pose": {
            "available": bool(pose_result.get("available", False)),
            "method": pose_result.get("method", "largest_pose_area"),
            "person_count": int(pose_result.get("person_count", 0)),
            "selected": bool(pose_result.get("selected", False)),
            "selected_person": selected_person,
            "reason": pose_result.get("reason"),
        },
        "pose_face_match": {
            "candidates": pose_face_match.get("candidates", []),
            "best_candidate": pose_face_match.get("best_candidate"),
            "regions": pose_face_match.get("regions"),
        },
        "reason": reason,
        "storage_warnings": _repository().skipped_embeddings,
    }


def verify_image(student_id: str, image_path: Path) -> dict[str, Any]:
    """Verify that one image face matches the requested student."""

    try:
        normalized_student_id = normalize_student_id(student_id)
        _repository().get_student(normalized_student_id)
        if not _repository().get_loaded_samples(normalized_student_id):
            raise EmbeddingStorageError(f"No valid embeddings loaded for student_id '{normalized_student_id}'")
    except InvalidStudentIdError as exc:
        raise ApiError(str(exc), 400, "INVALID_STUDENT_ID") from exc
    except StudentNotFoundError as exc:
        raise ApiError(str(exc), 404, "STUDENT_NOT_FOUND") from exc
    except EmbeddingStorageError as exc:
        raise ApiError(str(exc), 500, "CORRUPTED_STORED_EMBEDDING") from exc

    try:
        detection = _face_engine().extract_single_face(image_path)
    except FaceEngineError as exc:
        _handle_face_error(exc)

    try:
        match = _best_student_sample_match(normalized_student_id, detection.embedding)
    except EmbeddingStorageError as exc:
        raise ApiError(str(exc), 500, "CORRUPTED_STORED_EMBEDDING") from exc

    return {
        "verified": match["matched"],
        "similarity": match["similarity"],
        "threshold": match["threshold"],
        "student": match["student"],
        "sample_count": match["sample_count"],
        "loaded_sample_count": match["loaded_sample_count"],
        "best_sample": match["best_sample"],
        "best_sample_id": match["best_sample_id"],
        "best_sample_similarity": match["best_sample_similarity"],
        "face": detection.to_response(),
        "image_saved_as": str(image_path),
    }


def verify_image_for_selected_pose(
    student_id: str,
    image_path: Path,
    pose_result: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """Verify only the face matched to the selected closest-person pose."""

    try:
        normalized_student_id = normalize_student_id(student_id)
        _repository().get_student(normalized_student_id)
        if not _repository().get_loaded_samples(normalized_student_id):
            raise EmbeddingStorageError(f"No valid embeddings loaded for student_id '{normalized_student_id}'")
    except InvalidStudentIdError as exc:
        raise ApiError(str(exc), 400, "INVALID_STUDENT_ID") from exc
    except StudentNotFoundError as exc:
        raise ApiError(str(exc), 404, "STUDENT_NOT_FOUND") from exc
    except EmbeddingStorageError as exc:
        raise ApiError(str(exc), 500, "CORRUPTED_STORED_EMBEDDING") from exc

    pose_result = _estimate_pose_result(image_path, pose_result)
    selected_person = pose_result.get("selected_person")
    try:
        detections = _face_engine().extract_faces(image_path)
    except FaceEngineError as exc:
        _handle_face_error(exc)

    face_candidates = [detection.to_response() for detection in detections]
    image_shape = tuple(pose_result.get("image_shape") or _read_image_shape(image_path))
    pose_face_match = match_face_to_selected_pose(detections, selected_person, image_shape, current_app.config)
    if not pose_face_match.get("matched"):
        response = _no_pose_face_match_response(
            image_path,
            pose_result,
            pose_face_match.get("reason") or "No detected face matches the selected closest person pose",
            detections=face_candidates,
            mode="verify",
        )
        response.update(
            {
                "verified": False,
                "student": _student_response(_repository().get_student(normalized_student_id)),
                "sample_count": 0,
                "loaded_sample_count": len(_repository().get_loaded_samples(normalized_student_id)),
                "similarity": None,
                "threshold": _threshold(),
                "pose_face_match": {
                    "candidates": pose_face_match.get("candidates", []),
                    "best_candidate": pose_face_match.get("best_candidate"),
                    "regions": pose_face_match.get("regions"),
                },
            }
        )
        return response

    selected_detection = pose_face_match["selected_detection"]
    try:
        match = _best_student_sample_match(normalized_student_id, selected_detection.embedding)
    except EmbeddingStorageError as exc:
        raise ApiError(str(exc), 500, "CORRUPTED_STORED_EMBEDDING") from exc

    return {
        "verified": match["matched"],
        "similarity": match["similarity"],
        "threshold": match["threshold"],
        "student": match["student"],
        "sample_count": match["sample_count"],
        "loaded_sample_count": match["loaded_sample_count"],
        "best_sample": match["best_sample"],
        "best_sample_id": match["best_sample_id"],
        "best_sample_similarity": match["best_sample_similarity"],
        "face": selected_detection.to_response(),
        "face_matched_to_selected_pose": True,
        "identity": normalized_student_id if match["matched"] else None,
        "confidence": match["similarity"],
        "face_bbox": selected_detection.bbox,
        "face_count": len(detections),
        "evaluated_face_count": 1,
        "ignored_face_count": max(0, len(detections) - 1),
        "face_candidates": face_candidates,
        "pose": {
            "available": bool(pose_result.get("available", False)),
            "method": pose_result.get("method", "largest_pose_area"),
            "person_count": int(pose_result.get("person_count", 0)),
            "selected": bool(pose_result.get("selected", False)),
            "selected_person": selected_person,
            "reason": pose_result.get("reason"),
        },
        "pose_face_match": {
            "candidates": pose_face_match.get("candidates", []),
            "best_candidate": pose_face_match.get("best_candidate"),
            "regions": pose_face_match.get("regions"),
        },
        "image_saved_as": str(image_path),
        "reason": "Selected face matched pose and requested student" if match["matched"] else "Selected face matched pose but requested student did not match",
    }


@face_bp.get("/health")
def health_check():
    return success_response(face_health_data(), message="Face service is healthy")


@face_bp.post("/enroll")
def enroll_student():
    """Create a student with the first enrolled face sample."""

    repo = _repository()
    student_id = _required_form_value("student_id")
    student_name = _required_form_value("student_name")
    sample_label = (request.form.get("sample_label") or "initial").strip() or None

    try:
        student_id = normalize_student_id(student_id)
        if repo.student_exists(student_id):
            raise ApiError(
                f"student_id '{student_id}' already exists. Use /api/face/enroll-sample to add another face sample.",
                409,
                "STUDENT_ALREADY_EXISTS",
            )
    except InvalidStudentIdError as exc:
        raise ApiError(str(exc), 400, "INVALID_STUDENT_ID") from exc

    image_path = _save_request_image("enroll")

    try:
        detection = _face_engine().extract_single_face(image_path)
        result = repo.add_student(
            student_id=student_id,
            student_name=student_name,
            embedding=detection.embedding,
            source_image=image_path,
            face=detection.to_response(),
            sample_label=sample_label,
        )
    except FaceEngineError as exc:
        _handle_face_error(exc)
    except DuplicateStudentError as exc:
        raise ApiError(str(exc), 409, "STUDENT_ALREADY_EXISTS") from exc
    except InvalidStudentIdError as exc:
        raise ApiError(str(exc), 400, "INVALID_STUDENT_ID") from exc
    except MetadataStorageError as exc:
        raise ApiError(str(exc), 400, "VALIDATION_ERROR") from exc
    except RepositoryError as exc:
        current_app.logger.exception("Failed to enroll student")
        raise ApiError(str(exc), 500, "STORAGE_FAILURE") from exc

    return success_response(
        {
            "student": _student_response(result["student"]),
            "sample": _sample_response(result["sample"]),
            "face": detection.to_response(),
            "image_saved_as": str(image_path),
        },
        message="Student enrolled successfully with first face sample",
        status_code=201,
    )


@face_bp.post("/enroll-sample")
def enroll_student_sample():
    """Add an additional face sample for an existing student."""

    student_id = _required_form_value("student_id")
    sample_label = (request.form.get("sample_label") or "").strip() or None

    try:
        student_id = normalize_student_id(student_id)
        _repository().get_student(student_id)
    except InvalidStudentIdError as exc:
        raise ApiError(str(exc), 400, "INVALID_STUDENT_ID") from exc
    except StudentNotFoundError as exc:
        raise ApiError(str(exc), 404, "STUDENT_NOT_FOUND") from exc

    image_path = _save_request_image("enroll-sample")

    try:
        detection = _face_engine().extract_single_face(image_path)
        result = _repository().add_sample(
            student_id=student_id,
            embedding=detection.embedding,
            source_image=image_path,
            face=detection.to_response(),
            sample_label=sample_label,
        )
    except FaceEngineError as exc:
        _handle_face_error(exc)
    except InvalidStudentIdError as exc:
        raise ApiError(str(exc), 400, "INVALID_STUDENT_ID") from exc
    except StudentNotFoundError as exc:
        raise ApiError(str(exc), 404, "STUDENT_NOT_FOUND") from exc
    except RepositoryError as exc:
        current_app.logger.exception("Failed to add face sample")
        raise ApiError(str(exc), 500, "STORAGE_FAILURE") from exc

    return success_response(
        {
            "student": _student_response(result["student"]),
            "sample": _sample_response(result["sample"]),
            "face": detection.to_response(),
            "image_saved_as": str(image_path),
        },
        message="Face sample enrolled successfully",
        status_code=201,
    )


@face_bp.post("/identify")
def identify_faces():
    image_path = _save_request_image("identify")
    if _parse_bool(request.form.get("use_selected_pose"), default=True):
        return success_response(
            identify_image_for_selected_pose(image_path),
            message="Selected-pose face identification completed",
        )
    return success_response(identify_image(image_path), message="Face identification completed")


@face_bp.post("/verify")
def verify_student():
    raw_student_id = _required_form_value("student_id")
    image_path = _save_request_image("verify")
    if _parse_bool(request.form.get("use_selected_pose"), default=True):
        return success_response(
            verify_image_for_selected_pose(raw_student_id, image_path),
            message="Selected-pose face verification completed",
        )
    return success_response(verify_image(raw_student_id, image_path), message="Face verification completed")


@face_bp.delete("/students/<student_id>")
def delete_student(student_id: str):
    """Delete one student's metadata and all enrolled face files."""

    try:
        result = _repository().delete_student(
            student_id=student_id,
            upload_dir=current_app.config["FACE_UPLOAD_DIR"],
        )
    except InvalidStudentIdError as exc:
        raise ApiError(str(exc), 400, "INVALID_STUDENT_ID") from exc
    except StudentNotFoundError as exc:
        raise ApiError(str(exc), 404, "STUDENT_NOT_FOUND") from exc
    except RepositoryError as exc:
        current_app.logger.exception("Failed to delete student")
        raise ApiError(str(exc), 500, "STORAGE_DELETE_FAILURE") from exc

    return success_response(result, message="Student face data deleted successfully")


@face_bp.patch("/students/<student_id>/rename")
def rename_student(student_id: str):
    """Rename one student's face-data id without losing enrolled samples."""

    payload = request.get_json(silent=True) if request.is_json else {}
    new_student_id = (request.form.get("new_student_id") or payload.get("new_student_id") or "").strip()
    student_name = (request.form.get("student_name") or payload.get("student_name") or "").strip()
    if not new_student_id:
        raise ApiError("new_student_id is required", 400, "VALIDATION_ERROR", {"new_student_id": "required"})

    try:
        result = _repository().rename_student(
            old_student_id=student_id,
            new_student_id=new_student_id,
            student_name=student_name or None,
        )
    except InvalidStudentIdError as exc:
        raise ApiError(str(exc), 400, "INVALID_STUDENT_ID") from exc
    except StudentNotFoundError as exc:
        raise ApiError(str(exc), 404, "STUDENT_NOT_FOUND") from exc
    except DuplicateStudentError as exc:
        raise ApiError(str(exc), 409, "STUDENT_ALREADY_EXISTS") from exc
    except RepositoryError as exc:
        current_app.logger.exception("Failed to rename student face data")
        raise ApiError(str(exc), 500, "STORAGE_RENAME_FAILURE") from exc

    return success_response(result, message="Student face data renamed successfully")


@face_bp.post("/reload")
def reload_embeddings():
    """Reload stored metadata and embeddings without restarting Flask."""

    try:
        result = _repository().reload()
    except MetadataStorageError as exc:
        raise ApiError(str(exc), 500, "METADATA_STORAGE_FAILURE") from exc
    except RepositoryError as exc:
        current_app.logger.exception("Failed to reload embeddings")
        raise ApiError(str(exc), 500, "STORAGE_RELOAD_FAILED") from exc

    return success_response(result, message="Embeddings reloaded successfully")
