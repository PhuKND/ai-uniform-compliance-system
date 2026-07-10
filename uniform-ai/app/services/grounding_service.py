from __future__ import annotations

import warnings
from threading import Lock

import torch
from PIL import Image
from transformers import AutoModelForZeroShotObjectDetection, AutoProcessor

from uniform_validation.labels import CANONICAL_CLASS_NAME_TO_ID, REQUIRED_COMPONENT_KEYS

warnings.filterwarnings(
    "ignore",
    category=FutureWarning,
    module=r"transformers\.models\.grounding_dino\.processing_grounding_dino",
)


class GroundingService:
    SOURCE_DETECTOR = "grounding_dino_v2"

    PROMPT_LABELS = [
        "student",
        "white school shirt",
        "blue youth union shirt",
        "black long trousers",
        "red school scarf",
        "black school shorts",
        "white long trousers",
    ]

    REQUIRED_ITEM_ALIASES = {
        "ao_so_mi_trang": [
            "white school shirt",
            "white collared school shirt",
            "white uniform shirt",
            "school shirt",
            "white shirt",
            "áo sơ mi trắng",
        ],
        "ao_doan_thanh_nien": [
            "blue youth union shirt",
            "blue youth-union uniform shirt",
            "blue student-union shirt",
            "blue shirt",
            "youth union shirt",
            "áo đoàn thanh niên",
        ],
        "quan_tay_dai_den": [
            "black long trousers",
            "black school trousers",
            "long black trousers",
            "black trousers",
            "black pants",
            "trousers",
            "pants",
            "quần tây dài đen",
        ],
        "khan_quang_do": [
            "red school scarf",
            "red student scarf",
            "red neck scarf",
            "red scarf",
            "scarf",
            "khăn quàng đỏ",
        ],
        "quan_short_tay_den": [
            "black school shorts",
            "black uniform shorts",
            "black school short pants",
            "black shorts",
            "shorts",
            "quần short đen",
        ],
        "quan_dai_trang": [
            "white long trousers",
            "white uniform trousers",
            "white long school pants",
            "white trousers",
            "white pants",
            "quần dài trắng",
        ],
    }

    def __init__(self, config) -> None:
        self.config = config
        self.device = config.DEVICE
        self.model_id = config.GROUNDING_MODEL_ID

        self._processor = None
        self._model = None
        self._lock = Lock()

    def _load(self) -> None:
        if self._model is not None and self._processor is not None:
            return
        if not bool(getattr(self.config, "GROUNDING_DINO_V2_ENABLED", True)):
            raise RuntimeError("GROUNDING_DINO_V2_DISABLED: Grounding DINO V2 is disabled by configuration.")

        self._processor = AutoProcessor.from_pretrained(self.model_id)
        self._model = AutoModelForZeroShotObjectDetection.from_pretrained(
            self.model_id,
        ).to(self.device)
        self._model.eval()

    @staticmethod
    def _normalize_label(label: str) -> str:
        return label.lower().strip().replace(".", "")

    def canonical_label_for_text(self, label: str) -> str | None:
        normalized = self._normalize_label(label)
        normalized_aliases_by_class = {
            class_name: [self._normalize_label(alias) for alias in aliases]
            for class_name, aliases in self.REQUIRED_ITEM_ALIASES.items()
        }
        for class_name, normalized_aliases in normalized_aliases_by_class.items():
            if normalized in normalized_aliases:
                return class_name
        for class_name, aliases in self.REQUIRED_ITEM_ALIASES.items():
            normalized_aliases = normalized_aliases_by_class[class_name]
            if any(alias == normalized or alias in normalized or normalized in alias for alias in normalized_aliases):
                return class_name
        return None

    def _best_score_for_aliases(self, detections: list[dict], aliases: list[str]) -> float:
        best_score = 0.0
        norm_aliases = [self._normalize_label(alias) for alias in aliases]
        for det in detections:
            det_label = det["label"]
            for alias in norm_aliases:
                if alias == det_label or alias in det_label or det_label in alias:
                    best_score = max(best_score, det["score"])
        return best_score

    def _canonical_detection(self, index: int, label: str, score: float, box: list[float]) -> dict | None:
        canonical = self.canonical_label_for_text(label)
        if canonical is None:
            return None
        x1, y1, x2, y2 = [float(value) for value in box[:4]]
        bbox = [round(value, 2) for value in [x1, y1, x2, y2]]
        return {
            "detection_id": f"{self.SOURCE_DETECTOR}_{index:04d}",
            "index": index,
            "selection_rank": index,
            "class_id": CANONICAL_CLASS_NAME_TO_ID[canonical],
            "class_name": canonical,
            "source_label": self._normalize_label(label),
            "confidence": round(float(score), 4),
            "bbox": bbox,
            "bbox_xyxy": bbox,
            "xyxy": bbox,
            "bbox_area": round(float(max(0.0, x2 - x1) * max(0.0, y2 - y1)), 2),
            "x1": bbox[0],
            "y1": bbox[1],
            "x2": bbox[2],
            "y2": bbox[3],
            "source_detector": self.SOURCE_DETECTOR,
            "model_version": self.model_id,
            "pose_validation": None,
            "accepted": None,
            "rejection_reason": None,
        }

    def detect_required_items(self, image: Image.Image) -> dict:
        with self._lock:
            self._load()
            assert self._model is not None and self._processor is not None

            inputs = self._processor(
                images=image,
                text=[self.PROMPT_LABELS],
                return_tensors="pt",
            )
            inputs = {k: v.to(self.device) for k, v in inputs.items()}

            with torch.inference_mode():
                outputs = self._model(**inputs)

            with warnings.catch_warnings():
                warnings.filterwarnings(
                    "ignore",
                    message="The key `labels` is will return integer ids in `GroundingDinoProcessor.post_process_grounded_object_detection` output since v4.51.0.*",
                    category=FutureWarning,
                )
                processed = self._processor.post_process_grounded_object_detection(
                    outputs=outputs,
                    input_ids=inputs["input_ids"],
                    threshold=self.config.GROUNDING_BOX_THRESHOLD,
                    text_threshold=self.config.GROUNDING_TEXT_THRESHOLD,
                    target_sizes=[image.size[::-1]],
                )[0]

        label_values = processed.get("text_labels", processed.get("labels", []))

        detections = []
        uniform_detections = []
        for index, (box, score, label) in enumerate(zip(processed["boxes"], processed["scores"], label_values), start=1):
            normalized_label = self._normalize_label(str(label))
            score_value = float(score.item())
            bbox = [float(v) for v in box.tolist()]
            detections.append(
                {
                    "label": normalized_label,
                    "score": score_value,
                    "box": bbox,
                }
            )
            canonical = self._canonical_detection(index, normalized_label, score_value, bbox)
            if canonical is not None:
                uniform_detections.append(canonical)

        required_items = {}
        notes = []
        for key in REQUIRED_COMPONENT_KEYS:
            score = self._best_score_for_aliases(detections, self.REQUIRED_ITEM_ALIASES[key])
            present = score >= self.config.REQUIRED_ITEM_PRESENT_THRESHOLD
            required_items[key] = {"present": bool(present), "score": round(float(score), 3)}

            if not present:
                notes.append(f"Uniform component is missing or low confidence: {key}.")

        return {
            "required_items": required_items,
            "detections": detections,
            "uniform_detections": uniform_detections,
            "canonical_class_mapping": {str(CANONICAL_CLASS_NAME_TO_ID[key]): key for key in REQUIRED_COMPONENT_KEYS},
            "notes": notes,
        }

    def release(self) -> None:
        with self._lock:
            if self._model is not None:
                del self._model
                self._model = None
            if self.device.startswith("cuda"):
                torch.cuda.empty_cache()

    def status(self) -> dict:
        return {
            "available": bool(getattr(self.config, "GROUNDING_DINO_V2_ENABLED", True)),
            "model": self.model_id,
            "source_detector": self.SOURCE_DETECTOR,
            "device": self.device,
            "model_loaded": self._model is not None,
            "loads_on_demand": True,
            "classes": {str(CANONICAL_CLASS_NAME_TO_ID[key]): key for key in REQUIRED_COMPONENT_KEYS},
            "prompts": {key: self.REQUIRED_ITEM_ALIASES[key][0] for key in REQUIRED_COMPONENT_KEYS},
        }
