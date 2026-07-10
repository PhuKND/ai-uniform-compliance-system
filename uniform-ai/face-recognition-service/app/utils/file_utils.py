"""File validation and safe upload helpers."""

from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from werkzeug.datastructures import FileStorage
from werkzeug.utils import secure_filename


class FileUploadError(Exception):
    """Base exception for uploaded file validation errors."""


class NoFileUploadedError(FileUploadError):
    """Raised when a multipart request does not contain an image file."""


class InvalidFileTypeError(FileUploadError):
    """Raised when an uploaded file extension is not allowed."""


def validate_uploaded_image(
    uploaded_file: FileStorage | None,
    allowed_extensions: set[str],
) -> str:
    """Validate the image upload and return the normalized file extension."""

    if uploaded_file is None:
        raise NoFileUploadedError("No image file uploaded. Use multipart field 'image'.")

    filename = uploaded_file.filename or ""
    if filename.strip() == "":
        raise NoFileUploadedError("No image file uploaded. Use multipart field 'image'.")

    if "." not in filename:
        raise InvalidFileTypeError(
            f"Invalid file type. Allowed extensions: {sorted(allowed_extensions)}"
        )

    extension = filename.rsplit(".", 1)[1].lower()
    if extension not in allowed_extensions:
        raise InvalidFileTypeError(
            f"Invalid file type '.{extension}'. Allowed extensions: {sorted(allowed_extensions)}"
        )

    return extension


def save_uploaded_image(
    uploaded_file: FileStorage | None,
    upload_dir: Path,
    purpose: str,
    allowed_extensions: set[str],
) -> Path:
    """Save an uploaded image with a safe, unique filename."""

    extension = validate_uploaded_image(uploaded_file, allowed_extensions)
    assert uploaded_file is not None

    safe_name = secure_filename(uploaded_file.filename or f"image.{extension}")
    stem = Path(safe_name).stem[:80] or "image"
    timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    filename = f"{timestamp}_{uuid4().hex}_{stem}.{extension}"

    target_dir = Path(upload_dir) / purpose
    target_dir.mkdir(parents=True, exist_ok=True)
    destination = target_dir / filename
    uploaded_file.save(destination)

    return destination
