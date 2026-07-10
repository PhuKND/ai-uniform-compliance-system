"""Application configuration loaded from environment variables."""

from __future__ import annotations

import os
from pathlib import Path

from dotenv import load_dotenv


BASE_DIR = Path(__file__).resolve().parent.parent
load_dotenv(BASE_DIR / ".env")


def _parse_bool(value: str | None, default: bool = False) -> bool:
    if value is None or value == "":
        return default
    return value.strip().lower() in {"1", "true", "yes", "y", "on"}


def _parse_int(value: str | None, default: int) -> int:
    if value is None or value == "":
        return default
    try:
        return int(value)
    except ValueError:
        return default


def _parse_float(value: str | None, default: float) -> float:
    if value is None or value == "":
        return default
    try:
        return float(value)
    except ValueError:
        return default


def _parse_csv(value: str | None, default: list[str]) -> list[str]:
    if value is None or value.strip() == "":
        return default
    return [item.strip() for item in value.split(",") if item.strip()]


def _parse_origins(value: str | None) -> list[str] | str:
    if value is None or value.strip() == "":
        return ["http://localhost:3000", "http://localhost:5173", "http://127.0.0.1:5173"]
    if value.strip() == "*":
        return "*"
    return _parse_csv(value, [])


def _resolve_path(value: str | None, default: Path) -> Path:
    if value is None or value.strip() == "":
        return default.resolve()
    path = Path(value)
    if not path.is_absolute():
        path = BASE_DIR / path
    return path.resolve()


def _parse_det_size(value: str | None) -> tuple[int, int]:
    if value is None or value.strip() == "":
        return (640, 640)

    parts = [part.strip() for part in value.split(",") if part.strip()]
    if len(parts) != 2:
        return (640, 640)

    try:
        width, height = int(parts[0]), int(parts[1])
    except ValueError:
        return (640, 640)

    return (width, height)


def _insightface_providers() -> list[str]:
    explicit = os.getenv("INSIGHTFACE_PROVIDERS")
    if explicit:
        return _parse_csv(explicit, ["CPUExecutionProvider"])

    if _parse_bool(os.getenv("USE_GPU"), default=False):
        return ["CUDAExecutionProvider", "CPUExecutionProvider"]

    return ["CPUExecutionProvider"]


class Config:
    """Default runtime configuration for the face recognition API."""

    SECRET_KEY = os.getenv("SECRET_KEY", "change-me-in-production")
    DEBUG = _parse_bool(os.getenv("FLASK_DEBUG") or os.getenv("DEBUG"), default=False)
    HOST = os.getenv("FLASK_HOST") or os.getenv("HOST") or "0.0.0.0"
    PORT = _parse_int(os.getenv("FLASK_PORT") or os.getenv("PORT"), 5000)

    JSON_SORT_KEYS = False
    MAX_CONTENT_LENGTH = _parse_int(os.getenv("MAX_CONTENT_LENGTH_MB"), 8) * 1024 * 1024

    STORAGE_DIR = _resolve_path(os.getenv("STORAGE_DIR"), BASE_DIR / "storage")
    UPLOAD_DIR = _resolve_path(os.getenv("UPLOAD_DIR"), STORAGE_DIR / "uploads")
    EMBEDDING_DIR = _resolve_path(os.getenv("EMBEDDING_DIR"), STORAGE_DIR / "embeddings")
    METADATA_FILE = _resolve_path(os.getenv("METADATA_FILE"), STORAGE_DIR / "students.json")

    ALLOWED_EXTENSIONS = set(
        _parse_csv(os.getenv("ALLOWED_IMAGE_EXTENSIONS"), ["jpg", "jpeg", "png", "webp"])
    )

    SIMILARITY_THRESHOLD = _parse_float(
        os.getenv("SIMILARITY_THRESHOLD") or os.getenv("FACE_SIMILARITY_THRESHOLD"),
        0.5,
    )

    CORS_ORIGINS = _parse_origins(
        os.getenv("CORS_ORIGINS") or os.getenv("CORS_ALLOWED_ORIGINS")
    )

    INSIGHTFACE_MODEL_NAME = os.getenv("INSIGHTFACE_MODEL_NAME", "buffalo_l")
    INSIGHTFACE_MODEL_ROOT = os.getenv("INSIGHTFACE_MODEL_ROOT") or None
    INSIGHTFACE_CTX_ID = _parse_int(
        os.getenv("INSIGHTFACE_CTX_ID"),
        0 if _parse_bool(os.getenv("USE_GPU")) else -1,
    )
    INSIGHTFACE_DET_SIZE = _parse_det_size(os.getenv("INSIGHTFACE_DET_SIZE"))
    INSIGHTFACE_PROVIDERS = _insightface_providers()

    DEVICE = os.getenv("UNIFORM_DEVICE") or ("cuda:0" if _parse_bool(os.getenv("USE_GPU")) else "cpu")
    POSE_MODEL_PATH = os.getenv("UNIFORM_POSE_MODEL", "yolov8n-pose.pt")
    POSE_IMAGE_SIZE = _parse_int(os.getenv("UNIFORM_POSE_IMGSZ"), 640)
    POSE_PERSON_CONFIDENCE = _parse_float(os.getenv("UNIFORM_POSE_PERSON_CONF"), 0.25)
    POSE_MIN_CONFIDENCE = _parse_float(os.getenv("UNIFORM_POSE_MIN_KEYPOINT_CONF"), 0.40)
    POSE_MIN_VALID_KEYPOINTS = _parse_int(os.getenv("UNIFORM_POSE_MIN_VALID_KEYPOINTS"), 5)
    TARGET_PERSON_PADDING_RATIO = _parse_float(os.getenv("UNIFORM_TARGET_PERSON_PADDING_RATIO"), 0.15)
    MIN_FACE_POSE_OVERLAP_RATIO = _parse_float(os.getenv("UNIFORM_MIN_FACE_POSE_OVERLAP_RATIO"), 0.03)
    MAX_FACE_HEAD_DISTANCE_RATIO = _parse_float(os.getenv("UNIFORM_MAX_FACE_HEAD_DISTANCE_RATIO"), 0.28)
    MIN_FACE_POSE_MATCH_SCORE = _parse_float(os.getenv("UNIFORM_MIN_FACE_POSE_MATCH_SCORE"), 0.18)
    FACE_USE_SELECTED_POSE_BY_DEFAULT = _parse_bool(os.getenv("FACE_USE_SELECTED_POSE_BY_DEFAULT"), True)

    LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO").upper()
