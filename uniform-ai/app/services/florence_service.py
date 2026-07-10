from __future__ import annotations

import re
from threading import RLock
from typing import Any

import cv2
import numpy as np
import torch
from PIL import Image
from transformers import AutoModelForCausalLM, AutoProcessor


class FlorenceService:
    CAPTION_PROMPT = "<MORE_DETAILED_CAPTION>"
    NEGATION_PATTERN = re.compile(r"\b(no|not|without|free of)\b", flags=re.IGNORECASE)

    CONDITION_RULES = {
        "wrinkled": {
            "fail_keywords": ["wrinkled", "wrinkle", "creased", "crumpled", "rumpled"],
            "pass_keywords": ["neat", "smooth", "well pressed", "ironed", "tidy"],
        },
        "dirty": {
            "fail_keywords": ["dirty", "stained", "stain", "muddy", "soiled", "smudged", "dusty"],
            "pass_keywords": ["clean", "spotless", "well kept"],
        },
        "torn_or_damaged": {
            "fail_keywords": ["torn", "ripped", "hole", "damaged", "frayed", "tear"],
            "pass_keywords": ["intact", "undamaged", "no visible damage"],
        },
    }

    def __init__(self, config) -> None:
        self.config = config
        self.device = config.DEVICE
        self.model_id = config.FLORENCE_MODEL_ID
        self.dtype = torch.float16 if self.device.startswith("cuda") else torch.float32

        self._processor = None
        self._model = None
        self._lock = RLock()

    def _load(self) -> None:
        if self._model is not None and self._processor is not None:
            return

        self._model = AutoModelForCausalLM.from_pretrained(
            self.model_id,
            trust_remote_code=True,
            torch_dtype=self.dtype,
        ).to(self.device)
        self._model.eval()
        self._processor = AutoProcessor.from_pretrained(self.model_id, trust_remote_code=True)

    @staticmethod
    def _extract_text(parsed_answer: Any, raw_text: str) -> str:
        if isinstance(parsed_answer, dict):
            for value in parsed_answer.values():
                if isinstance(value, str) and value.strip():
                    return value.strip()
                if isinstance(value, list) and value:
                    maybe_text = value[0]
                    if isinstance(maybe_text, str) and maybe_text.strip():
                        return maybe_text.strip()
        return raw_text.strip()

    def _caption(self, image: Image.Image, max_new_tokens: int = 180) -> str:
        self._load()
        assert self._model is not None and self._processor is not None

        inputs = self._processor(text=self.CAPTION_PROMPT, images=image, return_tensors="pt")
        inputs = {k: v.to(self.device) if torch.is_tensor(v) else v for k, v in inputs.items()}
        if self.device.startswith("cuda") and "pixel_values" in inputs:
            inputs["pixel_values"] = inputs["pixel_values"].to(self.dtype)

        with torch.inference_mode():
            generated_ids = self._model.generate(
                input_ids=inputs["input_ids"],
                pixel_values=inputs["pixel_values"],
                max_new_tokens=max_new_tokens,
                num_beams=3,
                do_sample=False,
            )

        generated_text = self._processor.batch_decode(generated_ids, skip_special_tokens=False)[0]
        parsed_answer = self._processor.post_process_generation(
            generated_text,
            task=self.CAPTION_PROMPT,
            image_size=(image.width, image.height),
        )
        return self._extract_text(parsed_answer, generated_text)

    @staticmethod
    def _crop_with_padding(image: Image.Image, bbox: list[int] | None, padding_ratio: float = 0.08) -> Image.Image | None:
        if not bbox:
            return None
        x1, y1, x2, y2 = bbox
        if x2 <= x1 or y2 <= y1:
            return None

        w, h = image.size
        box_w = x2 - x1 + 1
        box_h = y2 - y1 + 1
        pad_x = int(box_w * padding_ratio)
        pad_y = int(box_h * padding_ratio)

        cx1 = max(0, x1 - pad_x)
        cy1 = max(0, y1 - pad_y)
        cx2 = min(w - 1, x2 + pad_x)
        cy2 = min(h - 1, y2 + pad_y)

        if cx2 - cx1 < 16 or cy2 - cy1 < 16:
            return None
        return image.crop((cx1, cy1, cx2 + 1, cy2 + 1))

    @staticmethod
    def _has_negation(prefix: str) -> bool:
        return FlorenceService.NEGATION_PATTERN.search(prefix) is not None

    def _count_keyword_hits(self, text: str, keywords: list[str]) -> int:
        hits = 0
        for keyword in keywords:
            pattern = re.compile(rf"\b{re.escape(keyword)}\b", flags=re.IGNORECASE)
            for match in pattern.finditer(text):
                prefix = text[max(0, match.start() - 24) : match.start()]
                if self._has_negation(prefix):
                    continue
                hits += 1
        return hits

    @staticmethod
    def _visual_metrics(crops: list[Image.Image]) -> dict[str, float]:
        if not crops:
            return {"wrinkled": 0.5, "dirty": 0.5, "torn_or_damaged": 0.5}

        wrinkle_scores: list[float] = []
        dirty_scores: list[float] = []
        damage_scores: list[float] = []

        for crop in crops:
            rgb = np.asarray(crop.convert("RGB"))
            gray = cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
            hsv = cv2.cvtColor(rgb, cv2.COLOR_RGB2HSV)

            lap_var = float(cv2.Laplacian(gray, cv2.CV_32F).var())
            wrinkle = float(np.clip((lap_var - 30.0) / 170.0, 0.0, 1.0))
            wrinkle_scores.append(wrinkle)

            dark_ratio = float(np.mean(hsv[:, :, 2] < 55))
            sat_dark_ratio = float(np.mean((hsv[:, :, 1] > 145) & (hsv[:, :, 2] < 140)))
            dirty = float(np.clip((dark_ratio * 2.8) + (sat_dark_ratio * 2.0), 0.0, 1.0))
            dirty_scores.append(dirty)

            edge_ratio = float(np.mean(cv2.Canny(gray, 80, 160) > 0))
            damage = float(np.clip((edge_ratio - 0.06) * 8.0, 0.0, 1.0))
            damage_scores.append(damage)

        return {
            "wrinkled": float(np.mean(wrinkle_scores)),
            "dirty": float(np.mean(dirty_scores)),
            "torn_or_damaged": float(np.mean(damage_scores)),
        }

    def _classify_condition(self, condition: str, text: str, visual_score: float) -> dict:
        rules = self.CONDITION_RULES[condition]
        fail_hits = self._count_keyword_hits(text, rules["fail_keywords"])
        pass_hits = self._count_keyword_hits(text, rules["pass_keywords"])

        if fail_hits > 0:
            if condition == "torn_or_damaged":
                return {"label": "fail", "score": round(min(1.0, 0.65 + 0.1 * fail_hits), 3)}
            if visual_score >= 0.35:
                return {"label": "fail", "score": round(min(1.0, 0.65 + 0.1 * fail_hits + 0.2 * visual_score), 3)}
            return {"label": "uncertain", "score": 0.55}

        if pass_hits > 0 and fail_hits == 0:
            if condition != "torn_or_damaged" and visual_score > 0.7:
                return {"label": "uncertain", "score": 0.5}
            return {"label": "pass", "score": round(min(1.0, 0.62 + 0.1 * pass_hits), 3)}

        if condition != "torn_or_damaged":
            if visual_score >= 0.82:
                return {"label": "fail", "score": round(0.58 + 0.25 * visual_score, 3)}
            if visual_score <= 0.15:
                return {"label": "pass", "score": 0.58}

        return {"label": "uncertain", "score": 0.5}

    def evaluate_appearance(self, image: Image.Image, parsing_result: dict | None) -> dict:
        with self._lock:
            return self._evaluate_appearance_locked(image, parsing_result)

    def _evaluate_appearance_locked(self, image: Image.Image, parsing_result: dict | None) -> dict:
        notes: list[str] = []

        full_caption = self._caption(image, max_new_tokens=200)
        caption_parts = [full_caption]

        crop_images: list[Image.Image] = []
        if parsing_result:
            upper_crop = self._crop_with_padding(image, parsing_result.get("bboxes", {}).get("upper_body"))
            lower_crop = self._crop_with_padding(image, parsing_result.get("bboxes", {}).get("lower_body"))
            if upper_crop is not None:
                crop_images.append(upper_crop)
                caption_parts.append(self._caption(upper_crop, max_new_tokens=120))
            if lower_crop is not None:
                crop_images.append(lower_crop)
                caption_parts.append(self._caption(lower_crop, max_new_tokens=120))

        caption_text = " ".join(caption_parts).lower()
        metrics = self._visual_metrics(crop_images)

        appearance = {}
        for condition in ["wrinkled", "dirty", "torn_or_damaged"]:
            appearance[condition] = self._classify_condition(condition, caption_text, metrics[condition])

        uncertain_count = sum(1 for item in appearance.values() if item["label"] == "uncertain")
        if uncertain_count >= 2:
            notes.append("Appearance evidence is weak. Manual review is recommended.")

        return {
            "appearance": appearance,
            "description": full_caption,
            "caption_text": caption_text,
            "visual_metrics": {key: round(float(value), 4) for key, value in metrics.items()},
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
            "available": True,
            "model": self.model_id,
            "device": self.device,
            "model_loaded": self._model is not None,
            "loads_on_demand": True,
        }
