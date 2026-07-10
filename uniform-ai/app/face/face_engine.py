"""Reusable InsightFace model wrapper and face matching helpers."""

from __future__ import annotations

import logging
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import cv2
import numpy as np


logger = logging.getLogger(__name__)


class FaceEngineError(Exception):
    """Base exception raised by the face engine."""


class ModelInitializationError(FaceEngineError):
    """Raised when InsightFace cannot be initialized."""


class ImageDecodeError(FaceEngineError):
    """Raised when an uploaded image cannot be read by OpenCV."""


class NoFaceDetectedError(FaceEngineError):
    """Raised when InsightFace finds no faces in an image."""


class MultipleFacesDetectedError(FaceEngineError):
    """Raised when exactly one face is required but multiple are present."""


@dataclass(frozen=True)
class FaceDetection:
    """Normalized face detection result used by the service layer."""

    index: int
    bbox: list[float]
    detection_score: float
    embedding: np.ndarray

    def to_response(self) -> dict[str, Any]:
        return {
            "index": self.index,
            "bbox": self.bbox,
            "detection_score": round(self.detection_score, 6),
        }


class FaceEngine:
    """Cached InsightFace wrapper loaded lazily per Flask process."""

    def __init__(
        self,
        model_name: str,
        providers: list[str],
        ctx_id: int,
        det_size: tuple[int, int],
        model_root: str | None = None,
    ) -> None:
        self.model_name = model_name
        self.providers = providers
        self.ctx_id = ctx_id
        self.det_size = det_size
        self.model_root = model_root
        self._model: Any | None = None
        self._lock = threading.Lock()

    @property
    def is_loaded(self) -> bool:
        return self._model is not None

    def _load_model(self) -> Any:
        if self._model is not None:
            return self._model

        with self._lock:
            if self._model is not None:
                return self._model

            try:
                from insightface.app import FaceAnalysis

                kwargs: dict[str, Any] = {
                    "name": self.model_name,
                    "providers": self.providers,
                }
                if self.model_root:
                    kwargs["root"] = self.model_root

                logger.info(
                    "Loading InsightFace model '%s' with providers=%s ctx_id=%s det_size=%s",
                    self.model_name,
                    self.providers,
                    self.ctx_id,
                    self.det_size,
                )
                model = FaceAnalysis(**kwargs)
                model.prepare(ctx_id=self.ctx_id, det_size=self.det_size)
                self._model = model
                logger.info("InsightFace model loaded")
                return self._model
            except Exception as exc:  # pragma: no cover - depends on local runtime
                logger.exception("Failed to initialize InsightFace")
                raise ModelInitializationError("Failed to initialize InsightFace model") from exc

    def extract_faces(self, image_path: Path) -> list[FaceDetection]:
        """Detect faces and extract normalized embeddings from an image."""

        image = self._read_image(image_path)
        return self.extract_faces_from_bgr(image)

    def extract_faces_from_bgr(self, image: np.ndarray) -> list[FaceDetection]:
        """Detect faces and extract normalized embeddings from an OpenCV BGR image."""

        if image is None or not isinstance(image, np.ndarray) or image.size == 0:
            raise ImageDecodeError("Could not decode uploaded image")

        model = self._load_model()

        try:
            faces = model.get(image)
        except Exception as exc:  # pragma: no cover - depends on local runtime
            logger.exception("InsightFace inference failed")
            raise FaceEngineError("InsightFace model inference failed") from exc

        detections: list[FaceDetection] = []
        for index, face in enumerate(faces):
            embedding = self._extract_embedding(face)
            bbox = [round(float(value), 2) for value in np.asarray(face.bbox).tolist()]
            detection_score = float(getattr(face, "det_score", 0.0))
            detections.append(
                FaceDetection(
                    index=index,
                    bbox=bbox,
                    detection_score=detection_score,
                    embedding=embedding,
                )
            )

        return detections

    def extract_single_face(self, image_path: Path) -> FaceDetection:
        """Extract one face embedding, enforcing exactly one detected face."""

        detections = self.extract_faces(image_path)
        if not detections:
            raise NoFaceDetectedError("No face found in the uploaded image")
        if len(detections) > 1:
            raise MultipleFacesDetectedError(
                f"Expected exactly one face, found {len(detections)} faces"
            )
        return detections[0]

    @staticmethod
    def cosine_similarity(left: np.ndarray, right: np.ndarray) -> float:
        """Compute cosine similarity between two embedding vectors."""

        left = np.asarray(left, dtype=np.float32)
        right = np.asarray(right, dtype=np.float32)

        left_norm = np.linalg.norm(left)
        right_norm = np.linalg.norm(right)
        if left_norm == 0.0 or right_norm == 0.0:
            return 0.0

        return float(np.dot(left, right) / (left_norm * right_norm))

    @staticmethod
    def _read_image(image_path: Path) -> np.ndarray:
        path = Path(image_path)
        if not path.exists():
            raise ImageDecodeError("Uploaded image file does not exist")

        try:
            buffer = np.fromfile(str(path), dtype=np.uint8)
            image = cv2.imdecode(buffer, cv2.IMREAD_COLOR)
        except Exception as exc:
            raise ImageDecodeError("Could not read uploaded image") from exc

        if image is None:
            raise ImageDecodeError("Could not decode uploaded image")

        return image

    @staticmethod
    def _extract_embedding(face: Any) -> np.ndarray:
        embedding = getattr(face, "normed_embedding", None)
        if embedding is None:
            embedding = getattr(face, "embedding", None)
        if embedding is None:
            raise FaceEngineError("InsightFace did not return a face embedding")

        vector = np.asarray(embedding, dtype=np.float32)
        norm = np.linalg.norm(vector)
        if norm == 0.0:
            raise FaceEngineError("InsightFace returned an invalid zero embedding")

        return vector / norm
