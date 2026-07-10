from __future__ import annotations

import logging
import os
from pathlib import Path

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont


logger = logging.getLogger(__name__)


def _font_candidates() -> list[Path]:
    candidates: list[Path] = []
    env_path = os.getenv("UNIFORM_FONT_PATH")
    if env_path:
        candidates.append(Path(env_path).expanduser())

    candidates.extend(
        [
            Path("C:/Windows/Fonts/arial.ttf"),
            Path("C:/Windows/Fonts/segoeui.ttf"),
            Path("C:/Windows/Fonts/tahoma.ttf"),
            Path("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"),
            Path("/usr/share/fonts/truetype/liberation2/LiberationSans-Regular.ttf"),
            Path("/usr/share/fonts/opentype/noto/NotoSans-Regular.ttf"),
            Path("/Library/Fonts/Arial Unicode.ttf"),
            Path("/System/Library/Fonts/Supplemental/Arial Unicode.ttf"),
        ]
    )

    try:
        from matplotlib import font_manager

        for family in ["DejaVu Sans", "Arial", "Noto Sans", "Liberation Sans", "Segoe UI"]:
            found = font_manager.findfont(family, fallback_to_default=False)
            if found:
                candidates.append(Path(found))
    except Exception:
        pass

    return candidates


def load_unicode_font(size: int) -> ImageFont.ImageFont:
    for path in _font_candidates():
        try:
            if path.exists():
                return ImageFont.truetype(str(path), size=size)
        except Exception:
            continue

    logger.warning("No Unicode TrueType font was found; Vietnamese accents may not render correctly.")
    return ImageFont.load_default()


def _text_size(draw: ImageDraw.ImageDraw, text: str, font: ImageFont.ImageFont) -> tuple[int, int]:
    bbox = draw.textbbox((0, 0), text, font=font)
    return int(bbox[2] - bbox[0]), int(bbox[3] - bbox[1])


def _wrap_text(
    draw: ImageDraw.ImageDraw,
    text: str,
    font: ImageFont.ImageFont,
    max_width: int,
) -> list[str]:
    words = text.split()
    if not words:
        return []

    lines: list[str] = []
    current = words[0]
    for word in words[1:]:
        candidate = f"{current} {word}"
        if _text_size(draw, candidate, font)[0] <= max_width:
            current = candidate
        else:
            lines.append(current)
            current = word
    lines.append(current)

    return lines


def _intersects(rect: tuple[int, int, int, int], other: tuple[int, int, int, int]) -> bool:
    return not (rect[2] <= other[0] or rect[0] >= other[2] or rect[3] <= other[1] or rect[1] >= other[3])


def _clip_label_rect(
    x: int,
    y: int,
    width: int,
    height: int,
    image_width: int,
    image_height: int,
) -> tuple[int, int, int, int]:
    x1 = min(max(0, x), max(0, image_width - width))
    y1 = min(max(0, y), max(0, image_height - height))
    return x1, y1, min(image_width, x1 + width), min(image_height, y1 + height)


def _pick_label_rect(
    anchor_x: int,
    anchor_y: int,
    box: tuple[int, int, int, int],
    label_width: int,
    label_height: int,
    image_width: int,
    image_height: int,
    used_rects: list[tuple[int, int, int, int]] | None,
) -> tuple[int, int, int, int]:
    x1, y1, x2, y2 = box
    candidates = [
        (anchor_x, y1 - label_height - 2),
        (anchor_x, y2 + 2),
        (x1, y1 + 2),
        (x2 - label_width, y1),
        (anchor_x, anchor_y),
    ]

    clipped = [
        _clip_label_rect(x, y, label_width, label_height, image_width, image_height)
        for x, y in candidates
    ]
    if not used_rects:
        return clipped[0]

    for rect in clipped:
        if not any(_intersects(rect, used) for used in used_rects):
            return rect
    return clipped[0]


def _text_color_for_background(color_bgr: tuple[int, int, int]) -> tuple[int, int, int]:
    b, g, r = color_bgr
    luminance = 0.299 * r + 0.587 * g + 0.114 * b
    return (0, 0, 0) if luminance > 170 else (255, 255, 255)


def draw_unicode_label(
    image_bgr: np.ndarray,
    text: str,
    box: tuple[int, int, int, int],
    color_bgr: tuple[int, int, int],
    font_size: int,
    used_rects: list[tuple[int, int, int, int]] | None = None,
) -> None:
    if not text:
        return

    image_height, image_width = image_bgr.shape[:2]
    font = load_unicode_font(font_size)
    image_rgb = cv2.cvtColor(image_bgr, cv2.COLOR_BGR2RGB)
    pil_image = Image.fromarray(image_rgb)
    draw = ImageDraw.Draw(pil_image)

    max_text_width = max(80, int(image_width * 0.56))
    lines = _wrap_text(draw, text, font, max_text_width)
    if not lines:
        return

    line_sizes = [_text_size(draw, line, font) for line in lines]
    line_gap = max(3, int(round(font_size * 0.18)))
    padding_x = max(6, int(round(font_size * 0.38)))
    padding_y = max(4, int(round(font_size * 0.28)))
    label_width = min(image_width, max(width for width, _ in line_sizes) + padding_x * 2)
    label_height = sum(height for _, height in line_sizes) + line_gap * (len(lines) - 1) + padding_y * 2

    x1, y1, x2, y2 = [int(round(value)) for value in box]
    label_rect = _pick_label_rect(
        anchor_x=x1,
        anchor_y=y1,
        box=(x1, y1, x2, y2),
        label_width=label_width,
        label_height=label_height,
        image_width=image_width,
        image_height=image_height,
        used_rects=used_rects,
    )
    lx1, ly1, lx2, ly2 = label_rect
    background_rgb = (color_bgr[2], color_bgr[1], color_bgr[0])
    text_rgb = _text_color_for_background(color_bgr)

    draw.rectangle(label_rect, fill=background_rgb)
    cursor_y = ly1 + padding_y
    for line, (_, line_height) in zip(lines, line_sizes):
        draw.text((lx1 + padding_x, cursor_y), line, font=font, fill=text_rgb)
        cursor_y += line_height + line_gap

    image_bgr[:, :, :] = cv2.cvtColor(np.asarray(pil_image), cv2.COLOR_RGB2BGR)
    if used_rects is not None:
        used_rects.append(label_rect)
