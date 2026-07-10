from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace


ROOT_DIR = Path(__file__).resolve().parent
APP_DIR = ROOT_DIR / "app"
if str(APP_DIR) not in sys.path:
    sys.path.insert(0, str(APP_DIR))
SERVICES_DIR = APP_DIR / "services"
if str(SERVICES_DIR) not in sys.path:
    sys.path.insert(0, str(SERVICES_DIR))

from rule_engine import RuleEngine
from uniform_validation.labels import CANONICAL_CLASS_ID_TO_NAME, REQUIRED_COMPONENT_KEYS
from uniform_validation.pose_yolo_association import CLASS_REGION_RULES, select_unique_per_class

try:
    from grounding_service import GroundingService
except ModuleNotFoundError:  # pragma: no cover - optional model stack may be absent in light test envs
    GroundingService = None

try:
    from yolov8_service import YoloV8Service
except ModuleNotFoundError:  # pragma: no cover - optional OpenCV stack may be absent in light test envs
    YoloV8Service = None


OFFICIAL_SIX_CLASS_ORDER = {
    0: "ao_so_mi_trang",
    1: "ao_doan_thanh_nien",
    2: "quan_tay_dai_den",
    3: "khan_quang_do",
    4: "quan_short_tay_den",
    5: "quan_dai_trang",
}


class UniformV2ContractTest(unittest.TestCase):
    def test_official_six_class_order_is_exact(self) -> None:
        self.assertEqual(CANONICAL_CLASS_ID_TO_NAME, OFFICIAL_SIX_CLASS_ORDER)
        self.assertEqual(list(REQUIRED_COMPONENT_KEYS), list(OFFICIAL_SIX_CLASS_ORDER.values()))

    def test_pose_regions_include_new_lower_body_classes(self) -> None:
        self.assertEqual(CLASS_REGION_RULES["ao_so_mi_trang"]["region"], "upper_body_bbox")
        self.assertEqual(CLASS_REGION_RULES["ao_doan_thanh_nien"]["region"], "upper_body_bbox")
        self.assertEqual(CLASS_REGION_RULES["khan_quang_do"]["region"], "neck_chest_bbox")
        self.assertEqual(CLASS_REGION_RULES["quan_tay_dai_den"]["region"], "lower_body_bbox")
        self.assertEqual(CLASS_REGION_RULES["quan_short_tay_den"]["region"], "lower_body_bbox")
        self.assertEqual(CLASS_REGION_RULES["quan_dai_trang"]["region"], "lower_body_bbox")

    def test_unique_per_class_uses_confidence_area_then_stable_order(self) -> None:
        detections = [
            self._detection("ao_so_mi_trang", 0.90, [0, 0, 20, 20], 10),
            self._detection("ao_so_mi_trang", 0.95, [0, 0, 5, 5], 11),
            self._detection("quan_tay_dai_den", 0.80, [0, 0, 10, 10], 20),
            self._detection("quan_tay_dai_den", 0.80, [0, 0, 30, 30], 21),
            self._detection("khan_quang_do", 0.70, [0, 0, 10, 10], 31, selection_rank=5),
            self._detection("khan_quang_do", 0.70, [0, 0, 10, 10], 30, selection_rank=2),
        ]

        final, removed = select_unique_per_class(detections)

        winners = {item["class_name"]: item for item in final}
        self.assertEqual(winners["ao_so_mi_trang"]["detection_id"], "det_11")
        self.assertEqual(winners["quan_tay_dai_den"]["detection_id"], "det_21")
        self.assertEqual(winners["khan_quang_do"]["detection_id"], "det_30")
        self.assertEqual(len(removed), 3)
        self.assertTrue(all(item["duplicate_of_detection_id"] for item in removed))

    def test_rule_engine_accepts_any_lower_body_option(self) -> None:
        config = SimpleNamespace(UNIFORM_SHIRT_POLICY="white_or_youth_union", REQUIRE_RED_SCARF=True)
        required_items = {
            key: {"present": False, "score": 0.0}
            for key in REQUIRED_COMPONENT_KEYS
        }
        required_items["ao_so_mi_trang"] = {"present": True, "score": 0.9}
        required_items["khan_quang_do"] = {"present": True, "score": 0.8}
        required_items["quan_short_tay_den"] = {"present": True, "score": 0.75}
        appearance = {
            "tucked_in": {"label": "pass", "score": 0.9},
            "wrinkled": {"label": "pass", "score": 0.9},
            "dirty": {"label": "pass", "score": 0.9},
            "torn_or_damaged": {"label": "pass", "score": 0.9},
        }

        result = RuleEngine(config).aggregate(required_items, appearance)

        self.assertEqual(
            result["uniform_policy"]["passed_component_count"],
            result["uniform_policy"]["required_component_count"],
        )
        self.assertEqual(result["overall"]["compliance"], "compliant")

    @unittest.skipIf(GroundingService is None, "Grounding DINO dependencies are not installed")
    def test_grounding_prompts_normalize_to_canonical_classes(self) -> None:
        service = GroundingService(
            SimpleNamespace(DEVICE="cpu", GROUNDING_MODEL_ID="unit-test", GROUNDING_DINO_V2_ENABLED=True)
        )

        self.assertEqual(
            GroundingService.PROMPT_LABELS,
            [
                "student",
                "white school shirt",
                "blue youth union shirt",
                "black long trousers",
                "red school scarf",
                "black school shorts",
                "white long trousers",
            ],
        )
        self.assertEqual(service.canonical_label_for_text("white school shirt"), "ao_so_mi_trang")
        self.assertEqual(service.canonical_label_for_text("blue youth union shirt"), "ao_doan_thanh_nien")
        self.assertEqual(service.canonical_label_for_text("black school shorts"), "quan_short_tay_den")
        self.assertEqual(service.canonical_label_for_text("white long trousers"), "quan_dai_trang")

    @unittest.skipIf(YoloV8Service is None, "YOLOv8 service dependencies are not installed")
    def test_yolov8_v2_resolves_only_package_best_pt(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            package_dir = root / "yolov8_6class"
            package_dir.mkdir()
            best = package_dir / "best.pt"
            best.write_bytes(b"unit-test")
            last = package_dir / "last.pt"
            last.write_bytes(b"unit-test")
            other_dir = root / "YOLOv8"
            other_dir.mkdir()
            other_best = other_dir / "best.pt"
            other_best.write_bytes(b"unit-test")

            service = YoloV8Service(self._yolo_config(package_dir, best))
            self.assertEqual(service._resolve_weights_path(), best.resolve())

            with self.assertRaisesRegex(RuntimeError, "production inference must use"):
                YoloV8Service(self._yolo_config(package_dir, last))._resolve_weights_path()

            with self.assertRaisesRegex(RuntimeError, "configured yolov8_6class package"):
                YoloV8Service(self._yolo_config(package_dir, other_best))._resolve_weights_path()

    @staticmethod
    def _detection(
        class_name: str,
        confidence: float,
        bbox: list[int],
        index: int,
        selection_rank: int | None = None,
    ) -> dict:
        item = {
            "class_name": class_name,
            "confidence": confidence,
            "bbox": bbox,
            "index": index,
            "detection_id": f"det_{index}",
            "source_detector": "unit",
        }
        if selection_rank is not None:
            item["selection_rank"] = selection_rank
        return item

    @staticmethod
    def _yolo_config(package_dir: Path, weights_path: Path) -> SimpleNamespace:
        return SimpleNamespace(
            YOLO_DIR=package_dir,
            YOLO_OUTPUT_DIR=package_dir,
            YOLO_CONFIDENCE=0.25,
            YOLO_IMAGE_SIZE=640,
            YOLO_MAX_DET=30,
            YOLO_SAVE_ANNOTATED=False,
            YOLOV8_V2_ENABLED=True,
            YOLOV8_V2_WEIGHTS_PATH=weights_path,
            YOLOV8_V2_MODEL_ID="unit-test",
            YOLOV8_V2_STRICT_CLASS_MAPPING=True,
        )


if __name__ == "__main__":
    unittest.main()
