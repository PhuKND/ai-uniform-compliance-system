from __future__ import annotations

from typing import Any

from .pose_regions import (
    HEAD_KEYS,
    bbox_center,
    build_pose_regions,
    nearest_keypoint_distance_ratio,
    overlap_ratio,
    point_inside_bbox,
)


def _face_bbox(face_detection: Any) -> list[float]:
    if isinstance(face_detection, dict):
        return [float(value) for value in face_detection.get("bbox", [0, 0, 0, 0])]
    return [float(value) for value in getattr(face_detection, "bbox", [0, 0, 0, 0])]


def _face_response(face_detection: Any) -> dict[str, Any]:
    if hasattr(face_detection, "to_response"):
        return face_detection.to_response()
    if isinstance(face_detection, dict):
        return dict(face_detection)
    return {"bbox": _face_bbox(face_detection)}


def _cfg(config: Any, name: str, default: Any) -> Any:
    if isinstance(config, dict):
        return config.get(name, default)
    getter = getattr(config, "get", None)
    if callable(getter):
        return getter(name, default)
    return getattr(config, name, default)


def _candidate_score(
    face_bbox: list[float],
    selected_person: dict[str, Any],
    regions: dict[str, Any],
    min_confidence: float,
) -> dict[str, Any]:
    center = bbox_center(face_bbox)
    head_bbox = regions["head_bbox"]
    body_bbox = regions["body_bbox"]
    overlap = overlap_ratio(face_bbox, head_bbox)
    body_overlap = overlap_ratio(face_bbox, body_bbox)
    center_inside_head = point_inside_bbox(center, head_bbox)
    center_inside_body = point_inside_bbox(center, body_bbox)
    distance_ratio = nearest_keypoint_distance_ratio(
        selected_person,
        HEAD_KEYS,
        center,
        body_bbox,
        min_confidence,
    )
    distance_score = 0.0 if distance_ratio is None else max(0.0, 1.0 - min(1.0, distance_ratio))

    score = (
        0.42 * min(1.0, overlap)
        + 0.23 * (1.0 if center_inside_head else 0.0)
        + 0.20 * distance_score
        + 0.10 * min(1.0, body_overlap)
        + 0.05 * (1.0 if center_inside_body else 0.0)
    )
    return {
        "score": round(float(score), 4),
        "head_overlap_ratio": round(float(overlap), 4),
        "body_overlap_ratio": round(float(body_overlap), 4),
        "center_inside_head": bool(center_inside_head),
        "center_inside_body": bool(center_inside_body),
        "head_distance_ratio": round(float(distance_ratio), 4) if distance_ratio is not None else None,
        "face_center": [round(center[0], 2), round(center[1], 2)],
    }


def match_face_to_selected_pose(
    face_detections: list[Any],
    selected_person: dict[str, Any] | None,
    image_shape: tuple[int, int] | tuple[int, int, int],
    config: Any,
) -> dict[str, Any]:
    if selected_person is None:
        return {
            "matched": False,
            "selected_detection": None,
            "reason": "No selected closest person pose is available",
            "candidates": [],
        }
    if not face_detections:
        return {
            "matched": False,
            "selected_detection": None,
            "reason": "No face detected in the uploaded image",
            "candidates": [],
        }

    min_confidence = float(_cfg(config, "POSE_MIN_CONFIDENCE", 0.4))
    padding_ratio = float(_cfg(config, "TARGET_PERSON_PADDING_RATIO", 0.15))
    min_overlap = float(_cfg(config, "MIN_FACE_POSE_OVERLAP_RATIO", 0.03))
    max_distance = float(_cfg(config, "MAX_FACE_HEAD_DISTANCE_RATIO", 0.28))
    min_score = float(_cfg(config, "MIN_FACE_POSE_MATCH_SCORE", 0.18))

    regions = build_pose_regions(selected_person, image_shape, min_confidence, padding_ratio)
    candidates: list[dict[str, Any]] = []
    for index, detection in enumerate(face_detections):
        bbox = _face_bbox(detection)
        metrics = _candidate_score(bbox, selected_person, regions, min_confidence)
        distance_ok = metrics["head_distance_ratio"] is not None and metrics["head_distance_ratio"] <= max_distance
        geometry_ok = (
            metrics["center_inside_body"]
            and (
                metrics["head_overlap_ratio"] >= min_overlap
                or metrics["center_inside_head"]
                or distance_ok
            )
        )
        candidates.append(
            {
                "index": index,
                "detection": detection,
                "face": _face_response(detection),
                "bbox": [round(float(v), 2) for v in bbox],
                "matched_geometry": bool(geometry_ok),
                **metrics,
            }
        )

    best = max(candidates, key=lambda item: item["score"])
    matched = bool(best["matched_geometry"] and best["score"] >= min_score)
    public_candidates = [
        {key: value for key, value in candidate.items() if key != "detection"}
        for candidate in candidates
    ]

    if not matched:
        return {
            "matched": False,
            "selected_detection": None,
            "reason": "No detected face matches the selected closest person pose",
            "candidates": public_candidates,
            "best_candidate": {key: value for key, value in best.items() if key != "detection"},
            "regions": regions,
        }

    return {
        "matched": True,
        "selected_detection": best["detection"],
        "reason": "Face box matches selected person head region",
        "candidates": public_candidates,
        "best_candidate": {key: value for key, value in best.items() if key != "detection"},
        "regions": regions,
        "match_score": best["score"],
    }
