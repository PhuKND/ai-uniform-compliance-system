from __future__ import annotations

import sys
import importlib.util
from pathlib import Path
from threading import Lock

import cv2
import numpy as np
import torch
import torchvision.transforms as transforms
from PIL import Image


class ParsingService:
    ATR_LABEL_IDS = {
        "upper_clothes": 4,
        "pants": 6,
    }

    def __init__(self, config) -> None:
        self.config = config
        self.device = config.DEVICE
        self.input_size = [512, 512]

        self._model = None
        self._upsample = torch.nn.Upsample(size=self.input_size, mode="bilinear", align_corners=True)

        self._schp_imported = False
        self._networks = None
        self._get_affine_transform = None
        self._transform_logits = None
        self._lock = Lock()

        self._transform = transforms.Compose(
            [
                transforms.ToTensor(),
                transforms.Normalize(mean=[0.406, 0.456, 0.485], std=[0.225, 0.224, 0.229]),
            ]
        )

    def _ensure_schp_imports(self) -> None:
        if self._schp_imported:
            return

        schp_root = str(Path(self.config.SCHP_REPO_DIR).resolve())
        if schp_root not in sys.path:
            sys.path.insert(0, schp_root)

        import networks  # type: ignore

        transforms_path = Path(schp_root) / "utils" / "transforms.py"
        spec = importlib.util.spec_from_file_location("schp_transforms", transforms_path)
        if spec is None or spec.loader is None:
            raise ImportError(f"Cannot load SCHP transforms from {transforms_path}")
        schp_transforms = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(schp_transforms)

        self._networks = networks
        self._get_affine_transform = schp_transforms.get_affine_transform
        self._transform_logits = schp_transforms.transform_logits
        self._schp_imported = True

    def _load(self) -> None:
        if self._model is not None:
            return
        self._ensure_schp_imports()

        ckpt_path = Path(self.config.SCHP_CHECKPOINT_PATH)
        if not ckpt_path.exists():
            raise FileNotFoundError(f"SCHP ATR checkpoint not found: {ckpt_path}")

        assert self._networks is not None
        model = self._networks.init_model("resnet101", num_classes=18, pretrained=None)

        checkpoint = torch.load(ckpt_path, map_location="cpu")
        state_dict = checkpoint["state_dict"] if isinstance(checkpoint, dict) and "state_dict" in checkpoint else checkpoint

        cleaned_state_dict = {}
        for name, value in state_dict.items():
            cleaned_name = name[7:] if name.startswith("module.") else name
            cleaned_state_dict[cleaned_name] = value

        model.load_state_dict(cleaned_state_dict, strict=True)
        model.to(self.device)
        model.eval()
        self._model = model

    def _compute_center_scale(self, width: int, height: int) -> tuple[np.ndarray, np.ndarray]:
        aspect_ratio = self.input_size[1] * 1.0 / self.input_size[0]
        box_w = float(width - 1)
        box_h = float(height - 1)

        center = np.zeros((2,), dtype=np.float32)
        center[0] = box_w * 0.5
        center[1] = box_h * 0.5

        if box_w > aspect_ratio * box_h:
            box_h = box_w / aspect_ratio
        elif box_w < aspect_ratio * box_h:
            box_w = box_h * aspect_ratio

        scale = np.array([box_w, box_h], dtype=np.float32)
        return center, scale

    @staticmethod
    def _bbox_from_mask(mask: np.ndarray) -> list[int] | None:
        ys, xs = np.where(mask)
        if ys.size == 0 or xs.size == 0:
            return None
        return [int(xs.min()), int(ys.min()), int(xs.max()), int(ys.max())]

    def _evaluate_tucked_in(self, upper_mask: np.ndarray, lower_mask: np.ndarray, image_height: int) -> dict:
        upper_ys, _ = np.where(upper_mask)
        lower_ys, _ = np.where(lower_mask)

        if upper_ys.size < 150 or lower_ys.size < 150:
            return {
                "label": "uncertain",
                "score": 0.0,
                "note": "Insufficient clothing pixels for tucked-in estimation.",
            }

        shirt_bottom = int(np.percentile(upper_ys, 95))
        waist_top = int(np.percentile(lower_ys, 10))
        delta = shirt_bottom - waist_top

        tolerance = max(1, int(image_height * self.config.TUCK_IN_TOLERANCE_RATIO))
        fail_limit = max(tolerance + 1, int(image_height * self.config.TUCK_IN_FAIL_RATIO))

        if delta <= tolerance:
            confidence = 1.0 - max(0, delta) / float(tolerance + 1)
            return {
                "label": "pass",
                "score": round(float(max(0.6, min(1.0, confidence))), 3),
                "note": "Upper-clothing boundary remains near or above waistband.",
            }

        if delta >= fail_limit:
            confidence = 0.55 + min(0.45, (delta - fail_limit) / float(max(1, image_height // 4)))
            return {
                "label": "fail",
                "score": round(float(min(1.0, confidence)), 3),
                "note": "Upper clothing likely extends below waistband.",
            }

        midpoint = (delta - tolerance) / float(max(1, fail_limit - tolerance))
        return {
            "label": "uncertain",
            "score": round(float(0.45 + 0.2 * midpoint), 3),
            "note": "Tucked-in signal is borderline.",
        }

    def parse(self, image: Image.Image) -> dict:
        with self._lock:
            self._load()
            assert self._model is not None
            assert self._get_affine_transform is not None
            assert self._transform_logits is not None

            image_rgb = np.asarray(image.convert("RGB"))
            image_bgr = cv2.cvtColor(image_rgb, cv2.COLOR_RGB2BGR)
            height, width = image_bgr.shape[:2]

            center, scale = self._compute_center_scale(width, height)
            trans = self._get_affine_transform(center, scale, 0, self.input_size)

            model_input = cv2.warpAffine(
                image_bgr,
                trans,
                (int(self.input_size[1]), int(self.input_size[0])),
                flags=cv2.INTER_LINEAR,
                borderMode=cv2.BORDER_CONSTANT,
                borderValue=(0, 0, 0),
            )

            tensor = self._transform(model_input).unsqueeze(0).to(self.device)
            with torch.inference_mode():
                output = self._model(tensor)
                upsample_output = self._upsample(output[0][-1][0].unsqueeze(0))

            upsample_output = upsample_output.squeeze().permute(1, 2, 0).detach().cpu().numpy()
            logits_result = self._transform_logits(
                upsample_output,
                center,
                scale,
                width,
                height,
                input_size=self.input_size,
            )
            parsing_map = np.argmax(logits_result, axis=2).astype(np.uint8)

        upper_mask = parsing_map == self.ATR_LABEL_IDS["upper_clothes"]
        trouser_mask = parsing_map == self.ATR_LABEL_IDS["pants"]
        lower_mask = trouser_mask

        tucked_in_result = self._evaluate_tucked_in(upper_mask, lower_mask, image_height=height)
        notes = [tucked_in_result.pop("note")]

        return {
            "parsing_map": parsing_map,
            "masks": {
                "upper_clothes": upper_mask,
                "quan_tay_dai_den": trouser_mask,
                "lower_clothes": lower_mask,
            },
            "bboxes": {
                "upper_body": self._bbox_from_mask(upper_mask),
                "lower_body": self._bbox_from_mask(lower_mask),
            },
            "tucked_in": tucked_in_result,
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
        checkpoint_path = Path(self.config.SCHP_CHECKPOINT_PATH)
        repo_dir = Path(self.config.SCHP_REPO_DIR)
        return {
            "available": checkpoint_path.exists() and repo_dir.exists(),
            "model": "SCHP ATR",
            "checkpoint": str(checkpoint_path),
            "repo_dir": str(repo_dir),
            "device": self.device,
            "model_loaded": self._model is not None,
        }
