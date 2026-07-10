from __future__ import annotations

from typing import Any

from .pose_regions import bbox_area, bbox_diagonal


def selection_score(person: dict[str, Any]) -> float:
    area = float(person.get("pose_bbox_area", 0.0))
    confidence = float(person.get("pose_confidence", 0.0))
    valid_count = int(person.get("valid_keypoint_count", 0))
    diagonal = float(person.get("pose_bbox_diagonal", 0.0))

    keypoint_bonus = min(1.0, valid_count / 10.0)
    return area * (0.65 + 0.25 * confidence + 0.10 * keypoint_bonus) + diagonal


def select_closest_person(
    people: list[dict[str, Any]],
    min_valid_keypoints: int = 5,
) -> dict[str, Any] | None:
    candidates = [
        person
        for person in people
        if int(person.get("valid_keypoint_count", 0)) >= min_valid_keypoints
        and bbox_area(person.get("pose_bbox", [0, 0, 0, 0])) > 0.0
    ]
    if not candidates:
        return None

    selected = max(candidates, key=selection_score)
    selected = dict(selected)
    selected["method"] = "largest_pose_area"
    selected["selection_score"] = round(selection_score(selected), 4)
    selected["pose_bbox_diagonal"] = round(bbox_diagonal(selected["pose_bbox"]), 2)
    return selected
