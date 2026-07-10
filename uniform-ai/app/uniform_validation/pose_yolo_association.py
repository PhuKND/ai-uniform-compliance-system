from __future__ import annotations

from typing import Any

from pose_estimation.pose_regions import (
    LOWER_BODY_KEYS,
    SCARF_KEYS,
    UPPER_BODY_KEYS,
    bbox_center,
    build_pose_regions,
    nearest_keypoint_distance_ratio,
    overlap_ratio,
    point_inside_bbox,
)
from .compliance_scoring import compliance_from_accepted_detections
from .labels import CANONICAL_CLASS_NAME_TO_ID


CLASS_REGION_RULES = {
    "ao_so_mi_trang": {
        "region": "upper_body_bbox",
        "keys": UPPER_BODY_KEYS,
        "accepted_reason": "Overlaps selected person upper-body region",
        "rejected_reason": "Detection is outside selected person upper-body region",
    },
    "ao_doan_thanh_nien": {
        "region": "upper_body_bbox",
        "keys": UPPER_BODY_KEYS,
        "accepted_reason": "Overlaps selected person upper-body region",
        "rejected_reason": "Detection is outside selected person upper-body region",
    },
    "quan_tay_dai_den": {
        "region": "lower_body_bbox",
        "keys": LOWER_BODY_KEYS,
        "accepted_reason": "Overlaps selected person lower-body region",
        "rejected_reason": "Detection is outside selected person lower-body region",
    },
    "khan_quang_do": {
        "region": "neck_chest_bbox",
        "keys": SCARF_KEYS,
        "accepted_reason": "Overlaps selected person neck/chest region",
        "rejected_reason": "Detection is outside selected person neck/chest region",
    },
    "quan_short_tay_den": {
        "region": "lower_body_bbox",
        "keys": LOWER_BODY_KEYS,
        "accepted_reason": "Overlaps selected person lower-body region",
        "rejected_reason": "Detection is outside selected person lower-body region",
    },
    "quan_dai_trang": {
        "region": "lower_body_bbox",
        "keys": LOWER_BODY_KEYS,
        "accepted_reason": "Overlaps selected person lower-body region",
        "rejected_reason": "Detection is outside selected person lower-body region",
    },
}


def _detection_bbox(detection: dict[str, Any]) -> list[float]:
    bbox = detection.get("bbox") or detection.get("bbox_xyxy")
    if bbox is None:
        bbox = [detection.get("x1", 0.0), detection.get("y1", 0.0), detection.get("x2", 0.0), detection.get("y2", 0.0)]
    return [float(value) for value in bbox[:4]]


def _normalize_detection(detection: dict[str, Any]) -> dict[str, Any]:
    bbox = [round(value, 2) for value in _detection_bbox(detection)]
    out = dict(detection)
    class_name = str(out.get("class_name", ""))
    if class_name in CANONICAL_CLASS_NAME_TO_ID:
        out["class_id"] = int(out.get("class_id", CANONICAL_CLASS_NAME_TO_ID[class_name]))
    x1, y1, x2, y2 = bbox
    bbox_area = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    if "index" not in out:
        out["index"] = int(out.get("selection_rank", 0) or 0)
    out.setdefault("detection_id", f"{out.get('source_detector', 'detector')}_{out.get('index', 0)}")
    out["bbox"] = bbox
    out["bbox_xyxy"] = bbox
    out["xyxy"] = bbox
    out["bbox_area"] = round(float(bbox_area), 2)
    out.setdefault("source_detector", "unknown")
    out.setdefault("model_version", out.get("model_id"))
    out.setdefault("pose_validation", None)
    out.setdefault("accepted", None)
    out.setdefault("rejection_reason", None)
    out.setdefault("selection_rank", int(out.get("index", 0) or 0))
    return out


def _duplicate_rejection_reason(winner: dict[str, Any], loser: dict[str, Any]) -> str:
    winner_conf = float(winner.get("confidence", 0.0))
    loser_conf = float(loser.get("confidence", 0.0))
    if loser_conf < winner_conf:
        return "duplicate_same_class_lower_confidence"

    winner_area = float(winner.get("bbox_area", 0.0))
    loser_area = float(loser.get("bbox_area", 0.0))
    if loser_area < winner_area:
        return "duplicate_same_class_tie_smaller_area"

    return "duplicate_same_class_stable_tie_break"


def _sort_key_for_unique_detection(detection: dict[str, Any]) -> tuple[float, float, int]:
    return (
        -float(detection.get("confidence", 0.0)),
        -float(detection.get("bbox_area", 0.0)),
        int(detection.get("selection_rank", detection.get("index", 0)) or 0),
    )


def select_unique_per_class(
    pose_accepted_detections: list[dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    grouped: dict[int, list[dict[str, Any]]] = {}
    for stable_index, detection in enumerate(pose_accepted_detections, start=1):
        normalized = _normalize_detection(detection)
        normalized["selection_rank"] = int(normalized.get("selection_rank") or stable_index)
        class_id = int(normalized.get("class_id", CANONICAL_CLASS_NAME_TO_ID.get(normalized.get("class_name", ""), -1)))
        grouped.setdefault(class_id, []).append(normalized)

    final: list[dict[str, Any]] = []
    removed: list[dict[str, Any]] = []
    for class_id in sorted(grouped):
        detections = grouped[class_id]
        ranked = sorted(detections, key=_sort_key_for_unique_detection)
        winner = dict(ranked[0])
        winner["accepted"] = True
        winner["rejection_reason"] = None
        final.append(winner)

        for loser in ranked[1:]:
            removed_item = dict(loser)
            removed_item["accepted"] = False
            removed_item["rejection_reason"] = _duplicate_rejection_reason(winner, removed_item)
            removed_item["duplicate_of_detection_id"] = winner.get("detection_id")
            removed.append(removed_item)

    final.sort(key=lambda detection: int(detection.get("class_id", 0)))
    return final, removed


def _cfg(config: Any, name: str, default: Any) -> Any:
    if isinstance(config, dict):
        return config.get(name, default)
    getter = getattr(config, "get", None)
    if callable(getter):
        return getter(name, default)
    return getattr(config, name, default)


def _thresholds(config: Any, class_name: str) -> dict[str, float]:
    min_overlap = float(_cfg(config, "MIN_COMPONENT_POSE_OVERLAP_RATIO", 0.2))
    if class_name == "khan_quang_do":
        min_overlap = float(_cfg(config, "MIN_SCARF_POSE_OVERLAP_RATIO", min(0.10, min_overlap)))
    return {
        "min_region_overlap": min_overlap,
        "min_body_overlap": float(_cfg(config, "MIN_COMPONENT_BODY_OVERLAP_RATIO", 0.05)),
        "max_center_distance": float(_cfg(config, "MAX_COMPONENT_CENTER_DISTANCE_RATIO", 0.35)),
        "min_keypoint_confidence": float(_cfg(config, "POSE_MIN_CONFIDENCE", 0.4)),
    }


def _validate_one_detection(
    detection: dict[str, Any],
    selected_person: dict[str, Any],
    regions: dict[str, Any],
    config: Any,
) -> dict[str, Any]:
    class_name = str(detection.get("class_name", ""))
    rule = CLASS_REGION_RULES.get(class_name)
    normalized = _normalize_detection(detection)

    if rule is None:
        reason = f"Unsupported uniform class for pose validation: {class_name}"
        return {
            **normalized,
            "accepted": False,
            "rejection_reason": reason,
            "pose_overlap_ratio": 0.0,
            "body_overlap_ratio": 0.0,
            "center_distance_ratio": None,
            "validation_reason": reason,
            "pose_validation": {
                "accepted": False,
                "reason": reason,
                "expected_region": None,
            },
        }

    bbox = normalized["bbox"]
    region_bbox = regions[rule["region"]]
    body_bbox = regions["body_bbox"]
    center = bbox_center(bbox)
    thresholds = _thresholds(config, class_name)

    region_overlap = overlap_ratio(bbox, region_bbox)
    body_overlap = overlap_ratio(bbox, body_bbox)
    center_inside_region = point_inside_bbox(center, region_bbox)
    center_inside_body = point_inside_bbox(center, body_bbox)
    distance_ratio = nearest_keypoint_distance_ratio(
        selected_person,
        rule["keys"],
        center,
        body_bbox,
        thresholds["min_keypoint_confidence"],
    )
    distance_ok = distance_ratio is not None and distance_ratio <= thresholds["max_center_distance"]
    overlap_ok = region_overlap >= thresholds["min_region_overlap"]
    body_ok = center_inside_body or body_overlap >= thresholds["min_body_overlap"]
    accepted = bool(body_ok and (overlap_ok or center_inside_region or distance_ok))

    if accepted:
        reason = rule["accepted_reason"]
    elif not body_ok:
        reason = "Detection is outside selected person body region"
    else:
        reason = rule["rejected_reason"]

    return {
        **normalized,
        "accepted": accepted,
        "rejection_reason": None if accepted else reason,
        "expected_region": rule["region"],
        "pose_overlap_ratio": round(float(region_overlap), 4),
        "body_overlap_ratio": round(float(body_overlap), 4),
        "center_distance_ratio": round(float(distance_ratio), 4) if distance_ratio is not None else None,
        "center_inside_expected_region": bool(center_inside_region),
        "center_inside_body_region": bool(center_inside_body),
        "validation_reason": reason,
        "pose_validation": {
            "accepted": accepted,
            "reason": reason,
            "expected_region": rule["region"],
            "pose_overlap_ratio": round(float(region_overlap), 4),
            "body_overlap_ratio": round(float(body_overlap), 4),
            "center_distance_ratio": round(float(distance_ratio), 4) if distance_ratio is not None else None,
        },
    }


def validate_yolo_detections_for_pose(
    raw_detections: list[dict[str, Any]],
    selected_person: dict[str, Any] | None,
    image_shape: tuple[int, int] | tuple[int, int, int],
    config: Any,
) -> dict[str, Any]:
    raw = [_normalize_detection(detection) for detection in raw_detections]

    if selected_person is None:
        rejected = [
            {
                **detection,
                "accepted": False,
                "rejection_reason": "selected_person_not_found",
                "pose_overlap_ratio": 0.0,
                "body_overlap_ratio": 0.0,
                "center_distance_ratio": None,
                "validation_reason": "No selected closest person pose is available",
                "pose_validation": {
                    "accepted": False,
                    "reason": "No selected closest person pose is available",
                    "expected_region": None,
                },
            }
            for detection in raw
        ]
        detector_trace = {
            "raw_detections": raw,
            "pose_accepted_detections": [],
            "final_unique_per_class_detections": [],
            "removed_duplicate_detections": [],
        }
        return {
            "raw_yolo_detections": raw,
            "raw_detections": raw,
            "pose_accepted_detections": [],
            "accepted_detections": [],
            "final_unique_per_class_detections": [],
            "removed_duplicate_detections": [],
            "rejected_detections": rejected,
            "compliance_result": compliance_from_accepted_detections([]),
            "selected_pose_regions": None,
            "detector_trace": detector_trace,
            "summary": {
                "raw_count": len(raw),
                "pose_accepted_count": 0,
                "final_unique_count": 0,
                "duplicate_removed_count": 0,
                "accepted_count": 0,
                "rejected_count": len(rejected),
                "reason": "No person pose detected",
            },
        }

    regions = build_pose_regions(
        selected_person,
        image_shape,
        min_confidence=float(_cfg(config, "POSE_MIN_CONFIDENCE", 0.4)),
        padding_ratio=float(_cfg(config, "TARGET_PERSON_PADDING_RATIO", 0.15)),
    )

    validated = [_validate_one_detection(detection, selected_person, regions, config) for detection in raw]
    pose_accepted = [dict(detection) for detection in validated if detection["accepted"]]
    rejected = [dict(detection) for detection in validated if not detection["accepted"]]
    final_unique, removed_duplicates = select_unique_per_class(pose_accepted)

    reason = None
    if raw and not pose_accepted:
        reason = "YOLO detections were found, but none belong to the selected closest person pose"

    detector_trace = {
        "raw_detections": raw,
        "pose_accepted_detections": pose_accepted,
        "final_unique_per_class_detections": final_unique,
        "removed_duplicate_detections": removed_duplicates,
    }

    return {
        "raw_yolo_detections": raw,
        "raw_detections": raw,
        "pose_accepted_detections": pose_accepted,
        "accepted_detections": final_unique,
        "final_unique_per_class_detections": final_unique,
        "removed_duplicate_detections": removed_duplicates,
        "rejected_detections": rejected,
        "compliance_result": compliance_from_accepted_detections(final_unique),
        "selected_pose_regions": regions,
        "detector_trace": detector_trace,
        "summary": {
            "raw_count": len(raw),
            "pose_accepted_count": len(pose_accepted),
            "final_unique_count": len(final_unique),
            "duplicate_removed_count": len(removed_duplicates),
            "accepted_count": len(final_unique),
            "rejected_count": len(rejected),
            "reason": reason,
        },
    }
