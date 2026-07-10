from __future__ import annotations

from typing import Any

from .labels import REQUIRED_COMPONENT_KEYS


def compliance_from_accepted_detections(accepted_detections: list[dict[str, Any]]) -> dict[str, bool]:
    present = {key: False for key in REQUIRED_COMPONENT_KEYS}
    for detection in accepted_detections:
        class_name = detection.get("class_name")
        if class_name in present:
            present[class_name] = True
    return present


def required_items_from_detections(
    accepted_detections: list[dict[str, Any]],
    present_threshold: float,
) -> dict[str, dict[str, Any]]:
    required_items = {key: {"present": False, "score": 0.0} for key in REQUIRED_COMPONENT_KEYS}
    for detection in accepted_detections:
        class_name = detection.get("class_name")
        if class_name not in required_items:
            continue
        confidence = round(float(detection.get("confidence", 0.0)), 3)
        if confidence > float(required_items[class_name]["score"]):
            required_items[class_name] = {
                "present": confidence >= present_threshold,
                "score": confidence,
            }
    return required_items
