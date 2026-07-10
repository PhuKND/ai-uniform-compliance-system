"""Flask application factory for the face recognition service."""

from __future__ import annotations

import logging
import sys
from pathlib import Path

from flask import Flask
from flask_cors import CORS
from werkzeug.exceptions import HTTPException, RequestEntityTooLarge

from app.config import Config
from app.services.face_engine import FaceEngine
from app.services.student_repository import StudentRepository
from app.utils.response_utils import ApiError, error_response

ROOT_APP_MODULE_DIR = Path(__file__).resolve().parents[2] / "app"
if ROOT_APP_MODULE_DIR.exists() and str(ROOT_APP_MODULE_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_APP_MODULE_DIR))

try:
    from pose_estimation import PoseEstimator
except Exception:  # pragma: no cover - optional when running the legacy service alone
    PoseEstimator = None

from app.routes.face_routes import face_bp


def create_app(config_class: type[Config] = Config) -> Flask:
    """Create and configure the Flask app."""

    app = Flask(__name__)
    app.config.from_object(config_class)

    _configure_logging(app.config["LOG_LEVEL"])
    _ensure_storage_dirs(app)
    _register_extensions(app)
    _register_routes(app)
    _register_error_handlers(app)

    return app


def _configure_logging(level_name: str) -> None:
    level = getattr(logging, level_name.upper(), logging.INFO)
    logging.basicConfig(
        level=level,
        format="%(asctime)s %(levelname)s [%(name)s] %(message)s",
    )


def _ensure_storage_dirs(app: Flask) -> None:
    app.config["STORAGE_DIR"].mkdir(parents=True, exist_ok=True)
    app.config["UPLOAD_DIR"].mkdir(parents=True, exist_ok=True)
    app.config["EMBEDDING_DIR"].mkdir(parents=True, exist_ok=True)
    app.config["METADATA_FILE"].parent.mkdir(parents=True, exist_ok=True)


def _register_extensions(app: Flask) -> None:
    CORS(
        app,
        resources={r"/api/*": {"origins": app.config["CORS_ORIGINS"]}},
        supports_credentials=False,
    )

    app.extensions["face_engine"] = FaceEngine(
        model_name=app.config["INSIGHTFACE_MODEL_NAME"],
        model_root=app.config["INSIGHTFACE_MODEL_ROOT"],
        providers=app.config["INSIGHTFACE_PROVIDERS"],
        ctx_id=app.config["INSIGHTFACE_CTX_ID"],
        det_size=app.config["INSIGHTFACE_DET_SIZE"],
    )
    app.extensions["student_repository"] = StudentRepository(
        embedding_dir=app.config["EMBEDDING_DIR"],
        metadata_file=app.config["METADATA_FILE"],
    )
    app.extensions["pose_estimator"] = PoseEstimator(app.config) if PoseEstimator is not None else None


def _register_routes(app: Flask) -> None:
    app.register_blueprint(face_bp, url_prefix="/api/face")


def _register_error_handlers(app: Flask) -> None:
    @app.errorhandler(ApiError)
    def handle_api_error(error: ApiError):
        return error_response(
            message=error.message,
            status_code=error.status_code,
            code=error.code,
            details=error.details,
        )

    @app.errorhandler(RequestEntityTooLarge)
    def handle_large_upload(error: RequestEntityTooLarge):
        max_mb = app.config["MAX_CONTENT_LENGTH"] // (1024 * 1024)
        return error_response(
            message=f"Uploaded file is too large. Max size is {max_mb} MB.",
            status_code=413,
            code="FILE_TOO_LARGE",
        )

    @app.errorhandler(HTTPException)
    def handle_http_error(error: HTTPException):
        return error_response(
            message=error.description,
            status_code=error.code or 500,
            code=error.name.upper().replace(" ", "_"),
        )

    @app.errorhandler(Exception)
    def handle_unexpected_error(error: Exception):
        app.logger.exception("Unhandled application error")
        return error_response(
            message="Internal server error",
            status_code=500,
            code="INTERNAL_SERVER_ERROR",
        )
