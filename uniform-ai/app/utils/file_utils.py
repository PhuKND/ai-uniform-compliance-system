from __future__ import annotations

from datetime import datetime
from pathlib import Path
from uuid import uuid4

from PIL import Image, ImageOps, UnidentifiedImageError
from werkzeug.datastructures import FileStorage
from werkzeug.utils import secure_filename


def ensure_runtime_directories(config) -> None:
    for path in [
        config.UPLOAD_DIR,
        config.OUTPUT_DIR,
        config.WEIGHTS_DIR,
        config.YOLO_OUTPUT_DIR,
        config.UNIFORM_STORAGE_DIR,
        config.UNIFORM_PRE_AI_IMAGE_DIR,
        config.UNIFORM_EVALUATION_RECORD_DIR,
        config.UNIFORM_SELECTION_RECORD_DIR,
        config.FACE_STORAGE_DIR,
        config.FACE_UPLOAD_DIR,
        config.FACE_EMBEDDING_DIR,
        config.FACE_METADATA_FILE.parent,
    ]:
        path.mkdir(parents=True, exist_ok=True)


def is_allowed_image_file(filename: str, allowed_extensions: set[str]) -> bool:
    suffix = Path(filename).suffix.lower()
    return suffix in allowed_extensions


def save_upload_file(upload: FileStorage, upload_dir: Path) -> Path:
    upload_dir.mkdir(parents=True, exist_ok=True)
    safe_name = secure_filename(upload.filename or "")
    suffix = Path(safe_name).suffix.lower() or ".jpg"
    unique_name = f"{datetime.utcnow().strftime('%Y%m%d_%H%M%S')}_{uuid4().hex}{suffix}"
    out_path = upload_dir / unique_name
    upload.save(out_path)
    return out_path


def load_rgb_image(image_path: Path) -> Image.Image:
    try:
        return ImageOps.exif_transpose(Image.open(image_path)).convert("RGB")
    except UnidentifiedImageError as exc:
        raise ValueError(f"Cannot decode image file: {image_path}") from exc


def remove_file_if_exists(file_path: Path) -> None:
    try:
        if file_path.exists():
            file_path.unlink()
    except OSError:
        # Best-effort cleanup.
        pass
