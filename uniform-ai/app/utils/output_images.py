from __future__ import annotations

import logging
import os
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageOps


logger = logging.getLogger(__name__)


def processed_max_dimension() -> int:
    raw = os.getenv("UNIFORM_PROCESSED_IMAGE_MAX_DIM", "1600").strip()
    try:
        return max(640, int(raw))
    except ValueError:
        return 1600


def processed_jpeg_quality() -> int:
    raw = os.getenv("UNIFORM_PROCESSED_IMAGE_JPEG_QUALITY", "88").strip()
    try:
        return max(60, min(95, int(raw)))
    except ValueError:
        return 88


def read_bgr_image(image_path: Path) -> np.ndarray:
    image_path = Path(image_path)
    try:
        with Image.open(image_path) as opened:
            rgb = ImageOps.exif_transpose(opened).convert("RGB")
            return cv2.cvtColor(np.asarray(rgb), cv2.COLOR_RGB2BGR)
    except Exception:
        buffer = np.fromfile(str(image_path), dtype=np.uint8)
        image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
        if image is None:
            raise RuntimeError(f"Could not read image: {image_path}")
        return image


def resize_to_max_dimension(image_bgr: np.ndarray, max_dimension: int | None = None) -> np.ndarray:
    max_dimension = max_dimension or processed_max_dimension()
    height, width = image_bgr.shape[:2]
    longest = max(width, height)
    if longest <= max_dimension:
        return image_bgr

    scale = max_dimension / float(longest)
    new_size = (max(1, int(round(width * scale))), max(1, int(round(height * scale))))
    return cv2.resize(image_bgr, new_size, interpolation=cv2.INTER_AREA)


def save_bgr_jpeg(
    image_bgr: np.ndarray,
    output_path: Path,
    *,
    quality: int | None = None,
    max_dimension: int | None = None,
) -> Path:
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    image_bgr = resize_to_max_dimension(image_bgr, max_dimension=max_dimension)
    quality = quality or processed_jpeg_quality()
    params = [
        int(cv2.IMWRITE_JPEG_QUALITY),
        int(quality),
        int(cv2.IMWRITE_JPEG_PROGRESSIVE),
        1,
        int(cv2.IMWRITE_JPEG_OPTIMIZE),
        1,
    ]
    success, encoded = cv2.imencode(".jpg", image_bgr, params)
    if not success:
        raise RuntimeError(f"Could not encode JPEG image: {output_path}")

    encoded.tofile(str(output_path))
    if not output_path.exists() or output_path.stat().st_size <= 0:
        raise RuntimeError(f"Encoded image was not written correctly: {output_path}")

    logger.info(
        "processed_image_saved path=%s size=%s width=%s height=%s quality=%s",
        output_path,
        output_path.stat().st_size,
        image_bgr.shape[1],
        image_bgr.shape[0],
        quality,
    )
    return output_path
