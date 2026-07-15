from __future__ import annotations

import io
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image

import app as uniform_app


class LightweightMethodSelectionTest(unittest.TestCase):
    def setUp(self) -> None:
        image = Image.new("RGB", (32, 48), color=(245, 245, 245))
        buffer = io.BytesIO()
        image.save(buffer, format="JPEG")
        self.image_bytes = buffer.getvalue()
        self.pil_image = image
        self.image_path = Path("mock-lightweight-input.jpg")
        self.pose_result = {
            "available": True,
            "model": "yolov8-pose",
            "model_path": "mock-pose.pt",
            "method": "largest_pose_area",
            "image_shape": [48, 32, 3],
            "people": [],
            "person_count": 0,
            "selected_person": None,
            "selected_person_index": None,
            "selected": False,
            "reason": "mocked pose result",
        }

    def _upload(self, method: str | None = None, method_field: str = "uniform_method") -> dict:
        data: dict = {
            "image": (io.BytesIO(self.image_bytes), "input.jpg"),
        }
        if method is not None:
            data[method_field] = method
        return data

    @staticmethod
    def _yolo_prediction() -> dict:
        return {
            "success": True,
            "model": "yolov8_v2",
            "model_id": "mock-yolov8-uniform",
            "model_version": "mock-yolov8-uniform",
            "source_detector": "yolov8_v2",
            "weights": "best.pt",
            "weights_path": "mock-best.pt",
            "confidence_threshold": 0.25,
            "image_size": 640,
            "max_det": 30,
            "class_mapping": {},
            "model_metadata": {},
            "detections": [],
            "raw_yolo_detections": [],
            "summary": {"total_detections": 0, "detected_classes": []},
            "annotated_path": None,
            "annotated_filename": None,
        }

    @staticmethod
    def _grounding_prediction() -> dict:
        return {
            "required_items": uniform_app.default_required_items(),
            "detections": [],
            "uniform_detections": [],
            "notes": [],
        }

    @staticmethod
    def _candidate(method: str, legacy_payload: dict, *_args) -> dict:
        return {
            "method": method,
            "evaluation_method": method,
            "status": "completed",
            "score": 100,
            "result_status": "Dat",
            "processed_image": "mock-processed.jpg",
            "processed_image_url": "/api/uniform/yolov8/outputs/mock-processed.jpg",
            "legacy_result": legacy_payload,
            "result": {"final_summary": {"score": 100, "is_compliant": True}},
        }

    def test_both_lightweight_endpoints_require_a_method_before_storing_upload(self) -> None:
        client = uniform_app.app.test_client()
        for endpoint in [
            "/api/uniform/evaluate/lightweight",
            "/api/ai/evaluate-student-lightweight",
        ]:
            with self.subTest(endpoint=endpoint), patch.object(uniform_app, "store_pre_ai_upload") as store_upload:
                response = client.post(endpoint, data=self._upload())

            self.assertEqual(response.status_code, 400)
            self.assertIn("uniform_method is required", response.get_data(as_text=True))
            store_upload.assert_not_called()

    def test_both_lightweight_endpoints_reject_an_unsupported_method(self) -> None:
        client = uniform_app.app.test_client()
        for endpoint in [
            "/api/uniform/evaluate/lightweight",
            "/api/ai/evaluate-student-lightweight",
        ]:
            with self.subTest(endpoint=endpoint), patch.object(uniform_app, "store_pre_ai_upload") as store_upload:
                response = client.post(endpoint, data=self._upload("UNSUPPORTED_DETECTOR"))

            self.assertEqual(response.status_code, 400)
            self.assertIn("uniform_method must be one of", response.get_data(as_text=True))
            store_upload.assert_not_called()

    def test_request_method_aliases_normalize_to_each_lightweight_detector(self) -> None:
        expected_methods = {
            "YOLOV8_V2": uniform_app.METHOD_LIGHTWEIGHT_YOLOV8,
            "GROUNDING_DINO_V2": uniform_app.METHOD_LIGHTWEIGHT_GROUNDING_DINO,
        }
        for method_field in ["uniform_method", "uniformMethod", "selected_method", "selectedMethod", "method"]:
            for request_method, expected_method in expected_methods.items():
                with self.subTest(method_field=method_field, request_method=request_method):
                    with uniform_app.app.test_request_context(
                        "/api/ai/evaluate-student-lightweight",
                        method="POST",
                        data={method_field: request_method},
                    ):
                        self.assertEqual(uniform_app.lightweight_method_from_request(), expected_method)

    def test_yolov8_selection_runs_only_yolov8_uniform_and_returns_one_candidate(self) -> None:
        method = uniform_app.METHOD_LIGHTWEIGHT_YOLOV8
        with (
            patch.object(uniform_app, "load_rgb_image", return_value=self.pil_image),
            patch.object(uniform_app.yolov8_service, "predict", return_value=self._yolo_prediction()) as yolo_predict,
            patch.object(uniform_app.grounding_service, "detect_required_items") as grounding_detect,
            patch.object(uniform_app.parsing_service, "parse") as schp_parse,
            patch.object(uniform_app.florence_service, "evaluate_appearance") as florence_evaluate,
            patch.object(
                uniform_app,
                "save_pose_validation_visualization",
                return_value=Path("mock-processed.jpg"),
            ),
            patch.object(
                uniform_app,
                "yolov8_output_url",
                return_value="/api/uniform/yolov8/outputs/mock-processed.jpg",
            ),
            patch.object(uniform_app, "_candidate_from_legacy_payload", side_effect=self._candidate),
            patch.object(uniform_app, "pre_ai_image_url", return_value="/api/uniform/pre-ai/mock.jpg"),
            patch.object(
                uniform_app.uniform_evaluation_repository,
                "save_evaluation",
                return_value=Path("mock-evaluation.json"),
            ),
            self.assertLogs(uniform_app.logger, level="INFO") as captured_logs,
        ):
            payload = uniform_app.run_lightweight_uniform_evaluation(
                self.image_path,
                {"path": str(self.image_path)},
                pose_result=self.pose_result,
                selected_method=method,
                insightface_executed=True,
            )

        yolo_predict.assert_called_once()
        grounding_detect.assert_not_called()
        schp_parse.assert_not_called()
        florence_evaluate.assert_not_called()
        self.assertEqual(payload["candidate_methods"], [method])
        self.assertEqual(len(payload["candidates"]), 1)
        self.assertIn("method_2_result", payload)
        self.assertNotIn("method_1_result", payload)
        self.assertEqual(payload["pipeline"]["selected_method"], method)
        self.assertEqual(payload["pipeline"]["component_detectors"], ["yolov8_v2"])
        log_text = "\n".join(captured_logs.output)
        self.assertIn(f"lightweight_method={method}", log_text)
        self.assertIn("executed=POSE,INSIGHTFACE,YOLOV8_UNIFORM", log_text)
        self.assertIn("skipped=GROUNDING_DINO,SCHP,FLORENCE_2", log_text)
        self.assertIn("processed_image_output_path=mock-processed.jpg status=completed", log_text)

    def test_grounding_selection_runs_only_grounding_and_returns_one_candidate(self) -> None:
        method = uniform_app.METHOD_LIGHTWEIGHT_GROUNDING_DINO
        with (
            patch.object(uniform_app, "load_rgb_image", return_value=self.pil_image),
            patch.object(
                uniform_app.grounding_service,
                "detect_required_items",
                return_value=self._grounding_prediction(),
            ) as grounding_detect,
            patch.object(uniform_app.grounding_service, "release") as grounding_release,
            patch.object(uniform_app.yolov8_service, "predict") as yolo_predict,
            patch.object(uniform_app.parsing_service, "parse") as schp_parse,
            patch.object(uniform_app.florence_service, "evaluate_appearance") as florence_evaluate,
            patch.object(
                uniform_app,
                "save_pose_validation_visualization",
                return_value=Path("mock-processed.jpg"),
            ),
            patch.object(
                uniform_app,
                "yolov8_output_url",
                return_value="/api/uniform/yolov8/outputs/mock-processed.jpg",
            ),
            patch.object(uniform_app, "_candidate_from_legacy_payload", side_effect=self._candidate),
            patch.object(uniform_app, "pre_ai_image_url", return_value="/api/uniform/pre-ai/mock.jpg"),
            patch.object(
                uniform_app.uniform_evaluation_repository,
                "save_evaluation",
                return_value=Path("mock-evaluation.json"),
            ),
            self.assertLogs(uniform_app.logger, level="INFO") as captured_logs,
        ):
            payload = uniform_app.run_lightweight_uniform_evaluation(
                self.image_path,
                {"path": str(self.image_path)},
                pose_result=self.pose_result,
                selected_method=method,
                insightface_executed=False,
            )

        grounding_detect.assert_called_once()
        grounding_release.assert_called_once()
        yolo_predict.assert_not_called()
        schp_parse.assert_not_called()
        florence_evaluate.assert_not_called()
        self.assertEqual(payload["candidate_methods"], [method])
        self.assertEqual(len(payload["candidates"]), 1)
        self.assertIn("method_1_result", payload)
        self.assertNotIn("method_2_result", payload)
        self.assertEqual(payload["pipeline"]["selected_method"], method)
        self.assertEqual(payload["pipeline"]["component_detectors"], ["grounding_dino_v2"])
        log_text = "\n".join(captured_logs.output)
        self.assertIn(f"lightweight_method={method}", log_text)
        self.assertIn("executed=POSE,GROUNDING_DINO", log_text)
        self.assertIn("skipped=INSIGHTFACE,YOLOV8_UNIFORM,SCHP,FLORENCE_2", log_text)
        self.assertIn("processed_image_output_path=mock-processed.jpg status=completed", log_text)

    def test_runner_logs_structured_failure_and_does_not_fall_back(self) -> None:
        method = uniform_app.METHOD_LIGHTWEIGHT_YOLOV8
        with (
            patch.object(uniform_app, "load_rgb_image", return_value=self.pil_image),
            patch.object(
                uniform_app,
                "run_lightweight_uniform_method_candidate",
                side_effect=RuntimeError("mock detector failure"),
            ) as candidate_runner,
            self.assertLogs(uniform_app.logger, level="INFO") as captured_logs,
        ):
            with self.assertRaisesRegex(RuntimeError, "mock detector failure"):
                uniform_app.run_lightweight_uniform_evaluation(
                    self.image_path,
                    {"path": str(self.image_path)},
                    pose_result=self.pose_result,
                    selected_method=method,
                    insightface_executed=True,
                )

        candidate_runner.assert_called_once()
        log_text = "\n".join(captured_logs.output)
        self.assertIn(f"lightweight_method={method}", log_text)
        self.assertIn("status=failed", log_text)
        self.assertNotIn("status=completed", log_text)

    def test_persistence_failure_keeps_the_original_error_and_logs_selected_method(self) -> None:
        method = uniform_app.METHOD_LIGHTWEIGHT_GROUNDING_DINO
        candidate = self._candidate(method, {})
        with (
            patch.object(uniform_app, "load_rgb_image", return_value=self.pil_image),
            patch.object(
                uniform_app,
                "run_lightweight_uniform_method_candidate",
                return_value=candidate,
            ),
            patch.object(uniform_app, "pre_ai_image_url", return_value="/api/uniform/pre-ai/mock.jpg"),
            patch.object(
                uniform_app.uniform_evaluation_repository,
                "save_evaluation",
                side_effect=OSError("mock persistence failure"),
            ),
            self.assertLogs(uniform_app.logger, level="INFO") as captured_logs,
        ):
            with self.assertRaisesRegex(OSError, "mock persistence failure"):
                uniform_app.run_lightweight_uniform_evaluation(
                    self.image_path,
                    {"path": str(self.image_path)},
                    pose_result=self.pose_result,
                    selected_method=method,
                    insightface_executed=True,
                )

        log_text = "\n".join(captured_logs.output)
        self.assertIn(f"lightweight_method={method}", log_text)
        self.assertIn("executed=POSE,INSIGHTFACE,GROUNDING_DINO", log_text)
        self.assertIn("skipped=YOLOV8_UNIFORM,SCHP,FLORENCE_2", log_text)
        self.assertIn("processed_image_output_path=mock-processed.jpg", log_text)
        self.assertIn("status=failed", log_text)
        self.assertNotIn("status=completed", log_text)


if __name__ == "__main__":
    unittest.main()
