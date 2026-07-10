from __future__ import annotations

import os
from collections.abc import Iterable
from pathlib import Path

import torch

try:
    from dotenv import load_dotenv
except ImportError:  # pragma: no cover - optional until dependencies are installed
    load_dotenv = None


class Config:
    def __init__(self) -> None:
        self.BASE_DIR = Path(__file__).resolve().parent.parent
        self.LEGACY_FACE_SERVICE_DIR = self.BASE_DIR / "face-recognition-service"
        self._load_env_files()

        self.UPLOAD_DIR = self._get_path("UNIFORM_UPLOAD_DIR", self.BASE_DIR / "uploads")
        self.OUTPUT_DIR = self._get_path("UNIFORM_OUTPUT_DIR", self.BASE_DIR / "outputs")
        self.WEIGHTS_DIR = self._get_path("UNIFORM_WEIGHTS_DIR", self.BASE_DIR / "weights")
        self.YOLO_DIR = self._get_path_compat(
            ["UNIFORM_AI_YOLOV8_V2_DIR", "UNIFORM_YOLOV8_V2_DIR"],
            self.BASE_DIR / "yolov8_6class",
        )
        self.YOLO_OUTPUT_DIR = self._get_path("UNIFORM_YOLO_OUTPUT_DIR", self.OUTPUT_DIR / "yolov8")
        self.UNIFORM_STORAGE_DIR = self._get_path("UNIFORM_STORAGE_DIR", self.BASE_DIR / "storage" / "uniform")
        self.UNIFORM_PRE_AI_IMAGE_DIR = self._get_path(
            "UNIFORM_PRE_AI_IMAGE_DIR",
            self.UNIFORM_STORAGE_DIR / "pre_ai",
        )
        self.UNIFORM_EVALUATION_RECORD_DIR = self._get_path(
            "UNIFORM_EVALUATION_RECORD_DIR",
            self.UNIFORM_STORAGE_DIR / "evaluations",
        )
        self.UNIFORM_SELECTION_RECORD_DIR = self._get_path(
            "UNIFORM_SELECTION_RECORD_DIR",
            self.UNIFORM_STORAGE_DIR / "selections",
        )
        self.YOLOV8_V2_ENABLED = self._get_bool("UNIFORM_AI_YOLOV8_V2_ENABLED", default=True)
        self.YOLOV8_V2_WEIGHTS_PATH = self._get_path("UNIFORM_AI_YOLOV8_V2_WEIGHTS", self.YOLO_DIR / "best.pt")
        self.YOLO_WEIGHTS_PATH = self.YOLOV8_V2_WEIGHTS_PATH
        self.YOLO_FALLBACK_WEIGHTS_PATH = None
        self.YOLOV8_V2_MODEL_ID = os.getenv(
            "UNIFORM_AI_YOLOV8_V2_MODEL_ID",
            "yolov8s_uniform_6class_20260630",
        ).strip()
        self.YOLOV8_V2_UNIQUE_PER_CLASS = self._get_bool("UNIFORM_AI_YOLOV8_V2_UNIQUE_PER_CLASS", default=True)
        self.YOLOV8_V2_STRICT_CLASS_MAPPING = self._get_bool("UNIFORM_AI_YOLOV8_V2_STRICT_CLASS_MAPPING", default=True)
        self.GROUNDING_DINO_V2_ENABLED = self._get_bool("UNIFORM_AI_GROUNDING_DINO_V2_ENABLED", default=True)
        self.SCHP_REPO_DIR = self._get_path("UNIFORM_SCHP_REPO_DIR", self.BASE_DIR / "third_party" / "schp")
        self.SCHP_CHECKPOINT_PATH = self._get_path(
            "UNIFORM_SCHP_CHECKPOINT",
            self.WEIGHTS_DIR / "exp-schp-201908301523-atr.pth",
        )

        self.PORT = int(os.getenv("UNIFORM_PORT", "5001"))
        self.DEBUG = os.getenv("UNIFORM_DEBUG", "0") == "1"
        self.PERSIST_UPLOADS = os.getenv("UNIFORM_PERSIST_UPLOADS", "0") == "1"
        self.MAX_CONTENT_LENGTH_MB = int(os.getenv("UNIFORM_MAX_CONTENT_MB", "15"))
        self.PRE_AI_IMAGE_MAX_BYTES = int(os.getenv("UNIFORM_PRE_AI_IMAGE_MAX_BYTES", str(1024 * 1024)))
        self.PRE_AI_IMAGE_MIN_BYTES = int(os.getenv("UNIFORM_PRE_AI_IMAGE_MIN_BYTES", str(768 * 1024)))
        self.PROCESSED_IMAGE_MAX_DIMENSION = int(os.getenv("UNIFORM_PROCESSED_IMAGE_MAX_DIM", "1600"))
        self.PROCESSED_IMAGE_JPEG_QUALITY = int(os.getenv("UNIFORM_PROCESSED_IMAGE_JPEG_QUALITY", "88"))

        self.CORS_ORIGINS = self._get_csv(
            ["UNIFORM_CORS_ORIGINS", "CORS_ORIGINS"],
            default=[
            "http://localhost:5173",
            "http://localhost:8080",
            ],
        )

        self.ALLOWED_IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}

        force_cpu = os.getenv("UNIFORM_FORCE_CPU", "0") == "1"
        self.DEVICE = "cuda:0" if torch.cuda.is_available() and not force_cpu else "cpu"

        self.GROUNDING_MODEL_ID = "IDEA-Research/grounding-dino-tiny"
        self.FLORENCE_MODEL_ID = "microsoft/Florence-2-base-ft"

        self.GROUNDING_BOX_THRESHOLD = float(os.getenv("UNIFORM_GD_BOX_THRESHOLD", "0.30"))
        self.GROUNDING_TEXT_THRESHOLD = float(os.getenv("UNIFORM_GD_TEXT_THRESHOLD", "0.25"))
        self.REQUIRED_ITEM_PRESENT_THRESHOLD = float(os.getenv("UNIFORM_REQUIRED_ITEM_THRESHOLD", "0.33"))

        self.YOLO_CONFIDENCE = float(self._get_first_env(["UNIFORM_AI_YOLOV8_V2_CONF", "UNIFORM_YOLO_CONF"], "0.25"))
        self.YOLO_IMAGE_SIZE = int(self._get_first_env(["UNIFORM_AI_YOLOV8_V2_IMGSZ", "UNIFORM_YOLO_IMGSZ"], "640"))
        self.YOLO_MAX_DET = int(self._get_first_env(["UNIFORM_AI_YOLOV8_V2_MAX_DET", "UNIFORM_YOLO_MAX_DET"], "30"))
        self.YOLO_SAVE_ANNOTATED = self._get_bool("UNIFORM_YOLO_SAVE_ANNOTATED", default=True)
        self.YOLOV8_V2_DEVICE = self._get_first_env(["UNIFORM_AI_YOLOV8_V2_DEVICE"], "auto")

        self.POSE_MODEL_PATH = os.getenv("UNIFORM_POSE_MODEL", "yolov8n-pose.pt").strip() or "yolov8n-pose.pt"
        self.POSE_IMAGE_SIZE = int(os.getenv("UNIFORM_POSE_IMGSZ", "640"))
        self.POSE_PERSON_CONFIDENCE = float(os.getenv("UNIFORM_POSE_PERSON_CONF", "0.25"))
        self.POSE_MIN_CONFIDENCE = float(os.getenv("UNIFORM_POSE_MIN_KEYPOINT_CONF", "0.40"))
        self.POSE_MIN_VALID_KEYPOINTS = int(os.getenv("UNIFORM_POSE_MIN_VALID_KEYPOINTS", "5"))
        self.TARGET_PERSON_PADDING_RATIO = float(os.getenv("UNIFORM_TARGET_PERSON_PADDING_RATIO", "0.15"))
        self.MIN_COMPONENT_POSE_OVERLAP_RATIO = float(os.getenv("UNIFORM_MIN_COMPONENT_POSE_OVERLAP_RATIO", "0.20"))
        self.MIN_SCARF_POSE_OVERLAP_RATIO = float(os.getenv("UNIFORM_MIN_SCARF_POSE_OVERLAP_RATIO", "0.08"))
        self.MIN_COMPONENT_BODY_OVERLAP_RATIO = float(os.getenv("UNIFORM_MIN_COMPONENT_BODY_OVERLAP_RATIO", "0.05"))
        self.MAX_COMPONENT_CENTER_DISTANCE_RATIO = float(os.getenv("UNIFORM_MAX_COMPONENT_CENTER_DISTANCE_RATIO", "0.35"))
        self.MIN_FACE_POSE_OVERLAP_RATIO = float(os.getenv("UNIFORM_MIN_FACE_POSE_OVERLAP_RATIO", "0.03"))
        self.MAX_FACE_HEAD_DISTANCE_RATIO = float(os.getenv("UNIFORM_MAX_FACE_HEAD_DISTANCE_RATIO", "0.28"))
        self.MIN_FACE_POSE_MATCH_SCORE = float(os.getenv("UNIFORM_MIN_FACE_POSE_MATCH_SCORE", "0.18"))
        self.SHOW_REJECTED_UNIFORM_DETECTIONS = self._get_bool("UNIFORM_SHOW_REJECTED_DETECTIONS", default=True)

        self.UNIFORM_SHIRT_POLICY = os.getenv("UNIFORM_SHIRT_POLICY", "white_or_youth_union").strip().lower()
        if self.UNIFORM_SHIRT_POLICY not in {"white_only", "youth_union_only", "white_or_youth_union"}:
            self.UNIFORM_SHIRT_POLICY = "white_or_youth_union"
        self.REQUIRE_RED_SCARF = self._get_bool("UNIFORM_REQUIRE_RED_SCARF", default=True)

        self.TUCK_IN_TOLERANCE_RATIO = float(os.getenv("UNIFORM_TUCK_IN_TOLERANCE_RATIO", "0.03"))
        self.TUCK_IN_FAIL_RATIO = float(os.getenv("UNIFORM_TUCK_IN_FAIL_RATIO", "0.11"))

        self.FACE_STORAGE_DIR = self._get_path_compat(
            ["FACE_STORAGE_DIR", "STORAGE_DIR"],
            self.BASE_DIR / "storage",
        )
        self.FACE_UPLOAD_DIR = self._get_path_compat(
            ["FACE_UPLOAD_DIR", "UPLOAD_DIR"],
            self.FACE_STORAGE_DIR / "uploads",
        )
        self.FACE_EMBEDDING_DIR = self._get_path_compat(
            ["FACE_EMBEDDING_DIR", "EMBEDDING_DIR"],
            self.FACE_STORAGE_DIR / "embeddings",
        )
        self.FACE_METADATA_FILE = self._get_path_compat(
            ["FACE_METADATA_FILE", "METADATA_FILE"],
            self.FACE_STORAGE_DIR / "students.json",
        )
        self.FACE_LEGACY_STORAGE_DIR = self.LEGACY_FACE_SERVICE_DIR / "storage"
        self.FACE_SIMILARITY_THRESHOLD = float(
            self._get_first_env(["FACE_SIMILARITY_THRESHOLD", "SIMILARITY_THRESHOLD"], "0.5")
        )
        self.FACE_ALLOWED_IMAGE_EXTENSIONS = {
            ext.lower().lstrip(".")
            for ext in self._get_csv(["FACE_ALLOWED_IMAGE_EXTENSIONS", "ALLOWED_IMAGE_EXTENSIONS"], ["jpg", "jpeg", "png", "webp"])
        }
        self.FACE_MAX_CONTENT_LENGTH_MB = int(
            self._get_first_env(["FACE_MAX_CONTENT_LENGTH_MB", "MAX_CONTENT_LENGTH_MB"], "8")
        )
        self.INSIGHTFACE_MODEL_NAME = self._get_first_env(["INSIGHTFACE_MODEL_NAME"], "buffalo_l")
        self.INSIGHTFACE_MODEL_ROOT = self._get_optional_path("INSIGHTFACE_MODEL_ROOT")
        self.INSIGHTFACE_CTX_ID = int(self._get_first_env(["INSIGHTFACE_CTX_ID"], "-1"))
        self.INSIGHTFACE_PROVIDERS = self._get_csv(["INSIGHTFACE_PROVIDERS"], ["CPUExecutionProvider"])
        self.INSIGHTFACE_DET_SIZE = self._get_int_pair("INSIGHTFACE_DET_SIZE", (640, 640))

    def _load_env_files(self) -> None:
        if load_dotenv is None:
            return

        root_env = self.BASE_DIR / ".env"
        legacy_face_env = self.LEGACY_FACE_SERVICE_DIR / ".env"
        if root_env.exists():
            load_dotenv(root_env, override=False)
        if legacy_face_env.exists():
            load_dotenv(legacy_face_env, override=False)

    def _get_path(self, env_name: str, default: Path) -> Path:
        raw = os.getenv(env_name)
        return self._resolve_path(raw, default)

    def _get_path_compat(self, env_names: Iterable[str], default: Path) -> Path:
        raw = self._get_first_env(env_names, None)
        return self._resolve_path(raw, default)

    def _resolve_path(self, raw: str | None, default: Path) -> Path:
        if not raw:
            return default.resolve()
        path = Path(raw).expanduser()
        if not path.is_absolute():
            path = self.BASE_DIR / path
        return path.resolve()

    def _get_optional_path(self, env_name: str) -> str | None:
        raw = os.getenv(env_name)
        if not raw or raw.strip() == "":
            return None
        return str(self._resolve_path(raw, self.BASE_DIR))

    @staticmethod
    def _get_first_env(env_names: Iterable[str], default: str | None) -> str | None:
        for env_name in env_names:
            raw = os.getenv(env_name)
            if raw is not None and raw.strip() != "":
                return raw.strip()
        return default

    def _get_csv(self, env_names: Iterable[str], default: list[str]) -> list[str]:
        raw = self._get_first_env(env_names, None)
        if raw is None:
            return list(default)
        values = [part.strip() for part in raw.split(",") if part.strip()]
        return values or list(default)

    @staticmethod
    def _get_int_pair(env_name: str, default: tuple[int, int]) -> tuple[int, int]:
        raw = os.getenv(env_name)
        if not raw:
            return default
        parts = [part.strip() for part in raw.split(",") if part.strip()]
        if len(parts) != 2:
            return default
        try:
            return int(parts[0]), int(parts[1])
        except ValueError:
            return default

    @staticmethod
    def _get_bool(env_name: str, default: bool = False) -> bool:
        raw = os.getenv(env_name)
        if raw is None:
            return default
        return raw.strip().lower() in {"1", "true", "yes", "on"}


def get_config() -> Config:
    return Config()
