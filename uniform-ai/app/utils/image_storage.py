from __future__ import annotations

import logging
from dataclasses import asdict, dataclass
from datetime import datetime, timezone
from io import BytesIO
from pathlib import Path
from uuid import uuid4

from PIL import Image, ImageOps, UnidentifiedImageError
from werkzeug.datastructures import FileStorage
from werkzeug.utils import secure_filename


logger = logging.getLogger(__name__)


@dataclass
class StoredPreAiImage:
    path: Path
    original_filename: str
    original_size_bytes: int
    stored_size_bytes: int
    compressed: bool
    warning: str | None = None
    width: int | None = None
    height: int | None = None
    iterations: int = 0

    def to_dict(self) -> dict:
        payload = asdict(self)
        payload["path"] = str(self.path)
        return payload


def _timestamp() -> str:
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")


def _safe_stem(filename: str) -> str:
    safe = secure_filename(filename or "image.jpg")
    return (Path(safe).stem[:80] or "image").strip("_") or "image"


def _unique_path(directory: Path, original_filename: str, suffix: str | None = None) -> Path:
    safe = secure_filename(original_filename or "image.jpg")
    extension = suffix or Path(safe).suffix.lower() or ".jpg"
    if not extension.startswith("."):
        extension = f".{extension}"
    return directory / f"{_timestamp()}_{uuid4().hex[:10]}_{_safe_stem(safe)}{extension}"


def _image_to_rgb(image: Image.Image) -> Image.Image:
    image = ImageOps.exif_transpose(image)
    if image.mode in {"RGBA", "LA"}:
        background = Image.new("RGB", image.size, (255, 255, 255))
        alpha = image.getchannel("A") if image.mode == "RGBA" else image.getchannel("A")
        background.paste(image.convert("RGBA"), mask=alpha)
        return background
    return image.convert("RGB")


def _jpeg_bytes(image: Image.Image, quality: int) -> bytes:
    buffer = BytesIO()
    image.save(buffer, format="JPEG", quality=quality, optimize=True, progressive=True)
    return buffer.getvalue()


def _resize_for_scale(image: Image.Image, scale: float) -> Image.Image:
    if scale >= 0.999:
        return image
    width, height = image.size
    new_width = max(1, int(round(width * scale)))
    new_height = max(1, int(round(height * scale)))
    resampling = getattr(getattr(Image, "Resampling", Image), "LANCZOS")
    return image.resize((new_width, new_height), resampling)


def _compress_to_target(
    source_path: Path,
    target_path: Path,
    min_bytes: int,
    max_bytes: int,
) -> StoredPreAiImage:
    try:
        with Image.open(source_path) as opened:
            rgb = _image_to_rgb(opened)
    except UnidentifiedImageError as exc:
        raise ValueError(f"Cannot decode image file for compression: {source_path}") from exc

    best_under: tuple[int, bytes, int, float, tuple[int, int]] | None = None
    iterations = 0
    quality_values = [95, 92, 90, 88, 85, 82, 80, 78, 75, 72, 70, 68, 65, 62, 60]
    scale = 1.0

    while scale >= 0.24:
        candidate_image = _resize_for_scale(rgb, scale)
        for quality in quality_values:
            iterations += 1
            data = _jpeg_bytes(candidate_image, quality)
            size = len(data)
            if size <= max_bytes:
                if best_under is None or size > best_under[0]:
                    best_under = (size, data, quality, scale, candidate_image.size)
                if size >= min_bytes:
                    target_path.write_bytes(data)
                    return StoredPreAiImage(
                        path=target_path,
                        original_filename=source_path.name,
                        original_size_bytes=source_path.stat().st_size,
                        stored_size_bytes=size,
                        compressed=True,
                        width=candidate_image.width,
                        height=candidate_image.height,
                        iterations=iterations,
                    )
        if best_under is not None and best_under[0] < min_bytes:
            break
        scale *= 0.90

    if best_under is None:
        scale = 0.22
        while scale >= 0.08 and best_under is None:
            candidate_image = _resize_for_scale(rgb, scale)
            for quality in [58, 55, 52, 48, 45, 40]:
                iterations += 1
                data = _jpeg_bytes(candidate_image, quality)
                size = len(data)
                if size <= max_bytes:
                    best_under = (size, data, quality, scale, candidate_image.size)
                    break
            scale *= 0.82

    if best_under is None:
        raise RuntimeError(f"Could not compress image below {max_bytes} bytes: {source_path}")

    size, data, quality, scale, dimensions = best_under
    target_path.write_bytes(data)
    warning = None
    if size < min_bytes:
        warning = (
            f"Compressed image is below preferred lower bound "
            f"({size} bytes < {min_bytes} bytes); kept best version under max size."
        )
        logger.warning("%s Source=%s quality=%s scale=%.3f", warning, source_path, quality, scale)

    return StoredPreAiImage(
        path=target_path,
        original_filename=source_path.name,
        original_size_bytes=source_path.stat().st_size,
        stored_size_bytes=size,
        compressed=True,
        warning=warning,
        width=dimensions[0],
        height=dimensions[1],
        iterations=iterations,
    )


def store_pre_ai_upload(
    upload: FileStorage,
    storage_dir: Path,
    min_bytes: int = 786_432,
    max_bytes: int = 1_048_576,
) -> StoredPreAiImage:
    if max_bytes <= 0:
        raise ValueError("max_bytes must be positive.")
    if min_bytes < 0:
        min_bytes = 0
    if min_bytes > max_bytes:
        min_bytes = max_bytes

    storage_dir = Path(storage_dir)
    storage_dir.mkdir(parents=True, exist_ok=True)
    original_filename = upload.filename or "image.jpg"
    saved_original = _unique_path(storage_dir, original_filename)
    upload.save(saved_original)

    original_size = saved_original.stat().st_size
    if original_size <= max_bytes:
        return StoredPreAiImage(
            path=saved_original,
            original_filename=original_filename,
            original_size_bytes=original_size,
            stored_size_bytes=original_size,
            compressed=False,
        )

    target_path = _unique_path(storage_dir, original_filename, suffix=".jpg")
    try:
        result = _compress_to_target(saved_original, target_path, min_bytes, max_bytes)
    finally:
        try:
            if saved_original.exists() and saved_original != target_path:
                saved_original.unlink()
        except OSError:
            logger.warning("Could not remove temporary pre-AI source image: %s", saved_original)

    result.original_filename = original_filename
    result.original_size_bytes = original_size
    return result
