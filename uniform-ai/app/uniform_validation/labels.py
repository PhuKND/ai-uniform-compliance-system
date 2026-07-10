from __future__ import annotations

from typing import Any


REQUIRED_COMPONENT_KEYS = [
    "ao_so_mi_trang",
    "ao_doan_thanh_nien",
    "quan_tay_dai_den",
    "khan_quang_do",
    "quan_short_tay_den",
    "quan_dai_trang",
]

CANONICAL_CLASS_ID_TO_NAME = {
    index: name for index, name in enumerate(REQUIRED_COMPONENT_KEYS)
}

CANONICAL_CLASS_NAME_TO_ID = {
    name: index for index, name in CANONICAL_CLASS_ID_TO_NAME.items()
}

VIETNAMESE_COMPONENT_LABELS = {
    "ao_so_mi_trang": "Áo sơ mi trắng",
    "ao_doan_thanh_nien": "Áo Đoàn Thanh niên",
    "quan_tay_dai_den": "Quần tây dài đen",
    "khan_quang_do": "Khăn quàng đỏ",
    "quan_short_tay_den": "Quần short đen",
    "quan_dai_trang": "Quần dài trắng",
}

SELECTED_STUDENT_LABEL = "học sinh được chọn"
REJECTED_OUTSIDE_BODY_LABEL = "bị từ chối: vì nằm ngoài cơ thể học sinh"

METHOD_GROUNDING_DINO = "GROUNDING_DINO_V2"
METHOD_YOLOV8 = "YOLOV8_V2"
METHOD_LIGHTWEIGHT_GROUNDING_DINO = "LIGHTWEIGHT_GROUNDING_DINO"
METHOD_LIGHTWEIGHT_YOLOV8 = "LIGHTWEIGHT_YOLOV8_UNIFORM"
LEGACY_METHOD_GROUNDING_DINO = "grounding_dino_schp_florence2"
LEGACY_METHOD_YOLOV8 = "yolov8_schp_florence2"

METHOD_FILENAME_PREFIXES = {
    METHOD_GROUNDING_DINO: "dino_schp_florence",
    METHOD_YOLOV8: "yolov8_schp_florence",
    METHOD_LIGHTWEIGHT_GROUNDING_DINO: "lightweight_dino",
    METHOD_LIGHTWEIGHT_YOLOV8: "lightweight_yolov8",
    LEGACY_METHOD_GROUNDING_DINO: "dino_schp_florence",
    LEGACY_METHOD_YOLOV8: "yolov8_schp_florence",
}

METHOD_DISPLAY_NAMES = {
    METHOD_GROUNDING_DINO: "Grounding DINO V2 + SCHP + Florence-2",
    METHOD_YOLOV8: "YOLOv8 V2 + SCHP + Florence-2",
    METHOD_LIGHTWEIGHT_GROUNDING_DINO: "YOLOv8 Pose + InsightFace + Grounding DINO",
    METHOD_LIGHTWEIGHT_YOLOV8: "YOLOv8 Pose + InsightFace + YOLOv8 uniform model",
    LEGACY_METHOD_GROUNDING_DINO: "Grounding DINO + SCHP + Florence-2",
    LEGACY_METHOD_YOLOV8: "YOLOv8 + SCHP + Florence-2",
}

ANNOTATION_COLORS_BGR = {
    "selected_student": (255, 145, 30),
    "rejected_outside_body": (35, 35, 225),
    "ao_so_mi_trang": (68, 172, 74),
    "ao_doan_thanh_nien": (216, 113, 32),
    "quan_tay_dai_den": (38, 156, 222),
    "khan_quang_do": (58, 63, 218),
    "quan_short_tay_den": (74, 116, 184),
    "quan_dai_trang": (214, 196, 116),
}


def vietnamese_component_label(class_name: Any) -> str:
    return VIETNAMESE_COMPONENT_LABELS.get(str(class_name), str(class_name))


def required_component_labels() -> list[str]:
    return [VIETNAMESE_COMPONENT_LABELS[key] for key in REQUIRED_COMPONENT_KEYS]


def normalize_component_key(value: Any) -> str | None:
    raw = str(value or "").strip()
    if raw in VIETNAMESE_COMPONENT_LABELS:
        return raw

    lowered = raw.lower()
    for key, label in VIETNAMESE_COMPONENT_LABELS.items():
        if lowered == label.lower():
            return key
    return None
