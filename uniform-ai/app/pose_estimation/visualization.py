from __future__ import annotations

from datetime import datetime
from pathlib import Path
from typing import Any

import cv2
import numpy as np

from uniform_validation.labels import (
    ANNOTATION_COLORS_BGR,
    REJECTED_OUTSIDE_BODY_LABEL,
    SELECTED_STUDENT_LABEL,
    vietnamese_component_label,
)
from utils.annotation_utils import draw_unicode_label
from utils.output_images import read_bgr_image, save_bgr_jpeg


POSE_EDGES = [
    ("left_eye", "right_eye"),
    ("left_eye", "nose"),
    ("right_eye", "nose"),
    ("left_ear", "left_eye"),
    ("right_ear", "right_eye"),
    ("left_shoulder", "right_shoulder"),
    ("left_shoulder", "left_elbow"),
    ("left_elbow", "left_wrist"),
    ("right_shoulder", "right_elbow"),
    ("right_elbow", "right_wrist"),
    ("left_shoulder", "left_hip"),
    ("right_shoulder", "right_hip"),
    ("left_hip", "right_hip"),
    ("left_hip", "left_knee"),
    ("left_knee", "left_ankle"),
    ("right_hip", "right_knee"),
    ("right_knee", "right_ankle"),
]

ACCEPTED_COLORS_BGR = {
    key: value
    for key, value in ANNOTATION_COLORS_BGR.items()
    if key not in {"selected_student", "rejected_outside_body"}
}


def _read_image(image_path: Path) -> np.ndarray:
    return read_bgr_image(image_path)


def _bbox_from_detection(detection: dict[str, Any]) -> list[float]:
    bbox = detection.get("bbox") or detection.get("bbox_xyxy")
    if not bbox:
        bbox = [detection.get("x1", 0), detection.get("y1", 0), detection.get("x2", 0), detection.get("y2", 0)]
    return [float(value) for value in bbox[:4]]


def _visible_keypoints(selected_person: dict[str, Any], min_confidence: float) -> dict[str, tuple[int, int]]:
    points: dict[str, tuple[int, int]] = {}
    for keypoint in selected_person.get("keypoints", []):
        if not keypoint.get("visible"):
            continue
        if float(keypoint.get("confidence", 0.0)) < min_confidence:
            continue
        points[str(keypoint["name"])] = (int(round(float(keypoint["x"]))), int(round(float(keypoint["y"]))))
    return points


def _draw_selected_pose(
    image: np.ndarray,
    selected_person: dict[str, Any],
    min_confidence: float,
    line_thickness: int,
    font_size: int,
    used_label_rects: list[tuple[int, int, int, int]],
) -> None:
    points = _visible_keypoints(selected_person, min_confidence)
    skeleton_color = (245, 185, 35)
    joint_color = (255, 245, 170)
    bbox_color = ANNOTATION_COLORS_BGR["selected_student"]

    for start, end in POSE_EDGES:
        if start in points and end in points:
            cv2.line(image, points[start], points[end], skeleton_color, line_thickness, cv2.LINE_AA)

    radius = max(3, line_thickness + 1)
    for point in points.values():
        cv2.circle(image, point, radius, joint_color, thickness=-1, lineType=cv2.LINE_AA)

    x1, y1, x2, y2 = [int(round(float(v))) for v in selected_person.get("pose_bbox", [0, 0, 0, 0])]
    cv2.rectangle(image, (x1, y1), (x2, y2), bbox_color, thickness=line_thickness)
    draw_unicode_label(
        image,
        SELECTED_STUDENT_LABEL,
        (x1, y1, x2, y2),
        bbox_color,
        font_size,
        used_label_rects,
    )


def _draw_detections(
    image: np.ndarray,
    detections: list[dict[str, Any]],
    accepted: bool,
    line_thickness: int,
    font_size: int,
    used_label_rects: list[tuple[int, int, int, int]],
) -> None:
    for detection in detections:
        x1, y1, x2, y2 = [int(round(value)) for value in _bbox_from_detection(detection)]
        class_name = str(detection.get("class_name", "uniform"))
        if accepted:
            color = ACCEPTED_COLORS_BGR.get(class_name, (85, 190, 100))
            label = vietnamese_component_label(class_name)
            thickness = line_thickness
        else:
            color = ANNOTATION_COLORS_BGR["rejected_outside_body"]
            label = REJECTED_OUTSIDE_BODY_LABEL
            thickness = max(1, line_thickness - 1)

        cv2.rectangle(image, (x1, y1), (x2, y2), color, thickness=thickness)
        draw_unicode_label(image, label, (x1, y1, x2, y2), color, font_size, used_label_rects)


def _safe_prefix(value: str | None) -> str | None:
    if not value:
        return None
    cleaned = "".join(ch if ch.isalnum() or ch in {"_", "-"} else "_" for ch in value.strip())
    return cleaned.strip("_") or None


def save_pose_validation_visualization(
    image_path: Path,
    selected_person: dict[str, Any] | None,
    accepted_detections: list[dict[str, Any]],
    rejected_detections: list[dict[str, Any]],
    output_dir: Path,
    min_keypoint_confidence: float = 0.4,
    show_rejected: bool = True,
    filename_prefix: str | None = None,
) -> Path:
    image_path = Path(image_path)
    image = _read_image(image_path)
    image_height, image_width = image.shape[:2]
    line_thickness = max(2, round(min(image_height, image_width) / 320))
    font_size = max(16, min(30, int(round(min(image_height, image_width) / 32))))
    used_label_rects: list[tuple[int, int, int, int]] = []

    _draw_detections(image, accepted_detections, True, line_thickness, font_size, used_label_rects)
    if show_rejected:
        _draw_detections(image, rejected_detections, False, line_thickness, font_size, used_label_rects)

    if selected_person is not None:
        _draw_selected_pose(
            image,
            selected_person,
            min_keypoint_confidence,
            line_thickness,
            font_size,
            used_label_rects,
        )

    output_dir.mkdir(parents=True, exist_ok=True)
    timestamp = datetime.utcnow().strftime("%Y%m%d_%H%M%S_%f")
    safe_prefix = _safe_prefix(filename_prefix)
    if safe_prefix:
        filename = f"{safe_prefix}_{timestamp}_{image_path.stem}.jpg"
    else:
        filename = f"{image_path.stem}_pose_validated_{timestamp}.jpg"
    output_path = output_dir / filename
    return save_bgr_jpeg(image, output_path)
