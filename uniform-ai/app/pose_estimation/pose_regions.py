from __future__ import annotations

import math
from typing import Any

import numpy as np


COCO_KEYPOINT_NAMES = [
    "nose",
    "left_eye",
    "right_eye",
    "left_ear",
    "right_ear",
    "left_shoulder",
    "right_shoulder",
    "left_elbow",
    "right_elbow",
    "left_wrist",
    "right_wrist",
    "left_hip",
    "right_hip",
    "left_knee",
    "right_knee",
    "left_ankle",
    "right_ankle",
]

NAME_TO_INDEX = {name: index for index, name in enumerate(COCO_KEYPOINT_NAMES)}

HEAD_KEYS = ["nose", "left_eye", "right_eye", "left_ear", "right_ear"]
SHOULDER_KEYS = ["left_shoulder", "right_shoulder"]
HIP_KEYS = ["left_hip", "right_hip"]
TORSO_KEYS = SHOULDER_KEYS + HIP_KEYS
UPPER_BODY_KEYS = HEAD_KEYS + SHOULDER_KEYS + ["left_elbow", "right_elbow"] + HIP_KEYS
LOWER_BODY_KEYS = HIP_KEYS + ["left_knee", "right_knee", "left_ankle", "right_ankle"]
SCARF_KEYS = ["nose", "left_shoulder", "right_shoulder"]


def clamp(value: float, low: float, high: float) -> float:
    return max(low, min(high, value))


def normalize_bbox(bbox: list[float] | tuple[float, ...]) -> list[float]:
    x1, y1, x2, y2 = [float(value) for value in bbox[:4]]
    if x2 < x1:
        x1, x2 = x2, x1
    if y2 < y1:
        y1, y2 = y2, y1
    return [x1, y1, x2, y2]


def clip_bbox(bbox: list[float], image_shape: tuple[int, int] | tuple[int, int, int]) -> list[float]:
    height, width = int(image_shape[0]), int(image_shape[1])
    x1, y1, x2, y2 = normalize_bbox(bbox)
    return [
        clamp(x1, 0.0, max(0.0, width - 1.0)),
        clamp(y1, 0.0, max(0.0, height - 1.0)),
        clamp(x2, 0.0, max(0.0, width - 1.0)),
        clamp(y2, 0.0, max(0.0, height - 1.0)),
    ]


def bbox_width(bbox: list[float]) -> float:
    x1, _, x2, _ = normalize_bbox(bbox)
    return max(0.0, x2 - x1)


def bbox_height(bbox: list[float]) -> float:
    _, y1, _, y2 = normalize_bbox(bbox)
    return max(0.0, y2 - y1)


def bbox_area(bbox: list[float]) -> float:
    return bbox_width(bbox) * bbox_height(bbox)


def bbox_center(bbox: list[float]) -> tuple[float, float]:
    x1, y1, x2, y2 = normalize_bbox(bbox)
    return (x1 + x2) / 2.0, (y1 + y2) / 2.0


def bbox_diagonal(bbox: list[float]) -> float:
    return math.hypot(bbox_width(bbox), bbox_height(bbox))


def expand_bbox(
    bbox: list[float],
    ratio: float,
    image_shape: tuple[int, int] | tuple[int, int, int],
    min_pad: float = 0.0,
) -> list[float]:
    x1, y1, x2, y2 = normalize_bbox(bbox)
    width = bbox_width(bbox)
    height = bbox_height(bbox)
    pad_x = max(min_pad, width * ratio)
    pad_y = max(min_pad, height * ratio)
    return clip_bbox([x1 - pad_x, y1 - pad_y, x2 + pad_x, y2 + pad_y], image_shape)


def intersection_area(left: list[float], right: list[float]) -> float:
    lx1, ly1, lx2, ly2 = normalize_bbox(left)
    rx1, ry1, rx2, ry2 = normalize_bbox(right)
    x1 = max(lx1, rx1)
    y1 = max(ly1, ry1)
    x2 = min(lx2, rx2)
    y2 = min(ly2, ry2)
    return max(0.0, x2 - x1) * max(0.0, y2 - y1)


def overlap_ratio(subject_bbox: list[float], region_bbox: list[float]) -> float:
    area = bbox_area(subject_bbox)
    if area <= 0.0:
        return 0.0
    return intersection_area(subject_bbox, region_bbox) / area


def iou(left: list[float], right: list[float]) -> float:
    inter = intersection_area(left, right)
    union = bbox_area(left) + bbox_area(right) - inter
    if union <= 0.0:
        return 0.0
    return inter / union


def point_inside_bbox(point: tuple[float, float], bbox: list[float]) -> bool:
    x, y = point
    x1, y1, x2, y2 = normalize_bbox(bbox)
    return x1 <= x <= x2 and y1 <= y <= y2


def _valid_keypoints(
    person: dict[str, Any],
    names: list[str] | None = None,
    min_confidence: float = 0.0,
) -> list[dict[str, float]]:
    keypoints = person.get("keypoints", [])
    wanted = set(names) if names else None
    out: list[dict[str, float]] = []
    for point in keypoints:
        name = str(point.get("name", ""))
        if wanted is not None and name not in wanted:
            continue
        confidence = float(point.get("confidence", 0.0))
        visible = bool(point.get("visible", confidence >= min_confidence))
        x = float(point.get("x", 0.0))
        y = float(point.get("y", 0.0))
        if visible and confidence >= min_confidence and x > 0.0 and y > 0.0:
            out.append({"name": name, "x": x, "y": y, "confidence": confidence})
    return out


def keypoint_bbox(
    person: dict[str, Any],
    names: list[str],
    image_shape: tuple[int, int] | tuple[int, int, int],
    min_confidence: float,
    fallback: list[float] | None = None,
    padding_ratio: float = 0.15,
) -> list[float] | None:
    points = _valid_keypoints(person, names, min_confidence)
    if not points:
        return fallback

    xs = [point["x"] for point in points]
    ys = [point["y"] for point in points]
    bbox = [min(xs), min(ys), max(xs), max(ys)]
    return expand_bbox(bbox, padding_ratio, image_shape, min_pad=6.0)


def _region_from_vertical_slice(
    body_bbox: list[float],
    image_shape: tuple[int, int] | tuple[int, int, int],
    y_start_ratio: float,
    y_end_ratio: float,
    x_pad_ratio: float = 0.04,
) -> list[float]:
    x1, y1, x2, y2 = normalize_bbox(body_bbox)
    height = max(1.0, y2 - y1)
    width = max(1.0, x2 - x1)
    return clip_bbox(
        [
            x1 - width * x_pad_ratio,
            y1 + height * y_start_ratio,
            x2 + width * x_pad_ratio,
            y1 + height * y_end_ratio,
        ],
        image_shape,
    )


def build_pose_regions(
    person: dict[str, Any],
    image_shape: tuple[int, int] | tuple[int, int, int],
    min_confidence: float = 0.4,
    padding_ratio: float = 0.15,
) -> dict[str, Any]:
    raw_body_bbox = person.get("pose_bbox") or person.get("bbox_xyxy") or person.get("bbox")
    if not raw_body_bbox:
        raise ValueError("Selected person does not include a pose bounding box.")

    body_bbox = expand_bbox(normalize_bbox(raw_body_bbox), padding_ratio, image_shape)
    body_width = max(1.0, bbox_width(body_bbox))
    body_height = max(1.0, bbox_height(body_bbox))

    upper_fallback = _region_from_vertical_slice(body_bbox, image_shape, 0.08, 0.62)
    lower_fallback = _region_from_vertical_slice(body_bbox, image_shape, 0.45, 1.0)
    head_fallback = _region_from_vertical_slice(body_bbox, image_shape, 0.0, 0.28, x_pad_ratio=0.0)
    neck_fallback = _region_from_vertical_slice(body_bbox, image_shape, 0.16, 0.42, x_pad_ratio=-0.12)

    upper = keypoint_bbox(
        person,
        UPPER_BODY_KEYS,
        image_shape,
        min_confidence,
        fallback=upper_fallback,
        padding_ratio=0.28,
    )
    lower = keypoint_bbox(
        person,
        LOWER_BODY_KEYS,
        image_shape,
        min_confidence,
        fallback=lower_fallback,
        padding_ratio=0.20,
    )
    head = keypoint_bbox(
        person,
        HEAD_KEYS,
        image_shape,
        min_confidence,
        fallback=head_fallback,
        padding_ratio=0.55,
    )
    neck_chest = keypoint_bbox(
        person,
        SCARF_KEYS,
        image_shape,
        min_confidence,
        fallback=neck_fallback,
        padding_ratio=0.32,
    )

    if upper is None:
        upper = upper_fallback
    if lower is None:
        lower = lower_fallback
    if head is None:
        head = head_fallback
    if neck_chest is None:
        neck_chest = neck_fallback

    # Keep the neck region near the upper torso even when only one head point is visible.
    nx1, ny1, nx2, ny2 = normalize_bbox(neck_chest)
    max_neck_height = body_height * 0.34
    if bbox_height(neck_chest) > max_neck_height:
        cy = (ny1 + ny2) / 2.0
        neck_chest = clip_bbox([nx1, cy - max_neck_height / 2.0, nx2, cy + max_neck_height / 2.0], image_shape)

    return {
        "body_bbox": [round(v, 2) for v in body_bbox],
        "upper_body_bbox": [round(v, 2) for v in upper],
        "lower_body_bbox": [round(v, 2) for v in lower],
        "head_bbox": [round(v, 2) for v in head],
        "neck_chest_bbox": [round(v, 2) for v in neck_chest],
        "body_width": round(body_width, 2),
        "body_height": round(body_height, 2),
        "body_diagonal": round(bbox_diagonal(body_bbox), 2),
    }


def nearest_keypoint_distance_ratio(
    person: dict[str, Any],
    names: list[str],
    point: tuple[float, float],
    body_bbox: list[float],
    min_confidence: float,
) -> float | None:
    keypoints = _valid_keypoints(person, names, min_confidence)
    if not keypoints:
        return None

    px, py = point
    distances = [math.hypot(px - kp["x"], py - kp["y"]) for kp in keypoints]
    diagonal = max(1.0, bbox_diagonal(body_bbox))
    return min(distances) / diagonal


def create_region_mask(
    regions: dict[str, Any],
    image_shape: tuple[int, int] | tuple[int, int, int],
    region_names: list[str],
) -> np.ndarray:
    height, width = int(image_shape[0]), int(image_shape[1])
    mask = np.zeros((height, width), dtype=np.uint8)
    for region_name in region_names:
        bbox = regions.get(region_name)
        if not bbox:
            continue
        x1, y1, x2, y2 = [int(round(v)) for v in clip_bbox(bbox, image_shape)]
        mask[y1 : y2 + 1, x1 : x2 + 1] = 1
    return mask
