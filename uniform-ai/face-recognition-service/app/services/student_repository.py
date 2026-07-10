"""Local student metadata and multi-sample embedding storage."""

from __future__ import annotations

import json
import logging
import re
import shutil
import threading
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from uuid import uuid4

import numpy as np


logger = logging.getLogger(__name__)
STUDENT_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,64}$")
SAMPLE_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,96}$")
METADATA_VERSION = 2


class RepositoryError(Exception):
    """Base exception for repository failures."""


class InvalidStudentIdError(RepositoryError):
    """Raised when a student id is unsafe for local file storage."""


class DuplicateStudentError(RepositoryError):
    """Raised when enrolling a student id that already exists."""


class StudentNotFoundError(RepositoryError):
    """Raised when a requested student id is not enrolled."""


class EmbeddingStorageError(RepositoryError):
    """Raised when an embedding file is missing or corrupted."""


class MetadataStorageError(RepositoryError):
    """Raised when the metadata JSON cannot be read or written."""


def normalize_student_id(student_id: str | None) -> str:
    """Normalize and validate a student id used for metadata and filenames."""

    normalized = (student_id or "").strip()
    if not STUDENT_ID_PATTERN.fullmatch(normalized):
        raise InvalidStudentIdError(
            "student_id must be 1-64 characters and contain only letters, numbers, '_' or '-'"
        )
    return normalized


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


class StudentRepository:
    """Persist students locally with multiple face samples per student."""

    def __init__(self, embedding_dir: Path, metadata_file: Path) -> None:
        self.embedding_dir = Path(embedding_dir)
        self.metadata_file = Path(metadata_file)
        self._lock = threading.RLock()
        self._metadata: dict[str, Any] = {"version": METADATA_VERSION, "students": {}}
        self._embedding_index: dict[str, list[dict[str, Any]]] = {}
        self._skipped_embeddings: list[dict[str, str]] = []

        self.embedding_dir.mkdir(parents=True, exist_ok=True)
        self.metadata_file.parent.mkdir(parents=True, exist_ok=True)
        if not self.metadata_file.exists():
            self._write_metadata(self._metadata)
        self.reload()

    @property
    def student_count(self) -> int:
        return len(self._metadata.get("students", {}))

    @property
    def total_sample_count(self) -> int:
        return sum(
            len(record.get("samples", []))
            for record in self._metadata.get("students", {}).values()
        )

    @property
    def loaded_embedding_count(self) -> int:
        return sum(len(entries) for entries in self._embedding_index.values())

    @property
    def skipped_embeddings(self) -> list[dict[str, str]]:
        return [dict(item) for item in self._skipped_embeddings]

    def reload(self) -> dict[str, Any]:
        """Reload metadata and all sample embeddings from disk."""

        with self._lock:
            metadata, migrated = self._read_metadata()
            students = metadata.get("students", {})
            embedding_index: dict[str, list[dict[str, Any]]] = {}
            skipped: list[dict[str, str]] = []

            for student_id, record in students.items():
                for sample in record.get("samples", []):
                    try:
                        embedding = self._read_embedding(sample)
                    except EmbeddingStorageError as exc:
                        logger.warning(
                            "Skipping embedding for student=%s sample=%s: %s",
                            student_id,
                            sample.get("sample_id"),
                            exc,
                        )
                        skipped.append(
                            {
                                "student_id": student_id,
                                "sample_id": str(sample.get("sample_id", "")),
                                "embedding_file": str(sample.get("embedding_file", "")),
                                "reason": str(exc),
                            }
                        )
                        continue

                    embedding_index.setdefault(student_id, []).append(
                        {
                            "sample": dict(sample),
                            "embedding": embedding,
                        }
                    )

            self._metadata = metadata
            self._embedding_index = embedding_index
            self._skipped_embeddings = skipped

            if migrated:
                self._write_metadata(self._metadata)

            return {
                "student_count": len(students),
                "total_sample_count": self.total_sample_count,
                "loaded_embeddings": self.loaded_embedding_count,
                "skipped_embeddings": self.skipped_embeddings,
                "metadata_version": METADATA_VERSION,
                "migrated": migrated,
            }

    def student_exists(self, student_id: str) -> bool:
        student_id = normalize_student_id(student_id)
        return student_id in self._metadata.get("students", {})

    def add_student(
        self,
        student_id: str,
        student_name: str,
        embedding: np.ndarray,
        source_image: Path | None = None,
        face: dict[str, Any] | None = None,
        sample_label: str | None = None,
    ) -> dict[str, Any]:
        """Create a new student with their first enrolled face sample."""

        student_id = normalize_student_id(student_id)
        student_name = (student_name or "").strip()
        if student_name == "":
            raise MetadataStorageError("student_name is required")

        with self._lock:
            students = self._metadata.setdefault("students", {})
            if student_id in students:
                raise DuplicateStudentError(f"student_id '{student_id}' already exists")

            now = _utc_now()
            sample = self._build_sample_record(
                student_id=student_id,
                source_image=source_image,
                face=face,
                sample_label=sample_label,
                created_at=now,
            )
            record = {
                "student_id": student_id,
                "student_name": student_name,
                "created_at": now,
                "updated_at": now,
                "samples": [sample],
            }

            self._write_embedding(sample["embedding_file"], embedding)
            students[student_id] = record
            self._metadata["version"] = METADATA_VERSION
            self._write_metadata(self._metadata)
            self._embedding_index[student_id] = [
                {"sample": dict(sample), "embedding": self._normalize_embedding(embedding)}
            ]
            return {"student": dict(record), "sample": dict(sample)}

    def add_sample(
        self,
        student_id: str,
        embedding: np.ndarray,
        source_image: Path | None = None,
        face: dict[str, Any] | None = None,
        sample_label: str | None = None,
    ) -> dict[str, Any]:
        """Add an extra face sample for an existing student."""

        student_id = normalize_student_id(student_id)
        with self._lock:
            students = self._metadata.setdefault("students", {})
            record = students.get(student_id)
            if record is None:
                raise StudentNotFoundError(f"student_id '{student_id}' was not found")

            sample = self._build_sample_record(
                student_id=student_id,
                source_image=source_image,
                face=face,
                sample_label=sample_label,
            )

            self._write_embedding(sample["embedding_file"], embedding)
            record.setdefault("samples", []).append(sample)
            record["updated_at"] = sample["created_at"]
            self._metadata["version"] = METADATA_VERSION
            self._write_metadata(self._metadata)
            self._embedding_index.setdefault(student_id, []).append(
                {"sample": dict(sample), "embedding": self._normalize_embedding(embedding)}
            )
            return {"student": dict(record), "sample": dict(sample)}

    def get_student(self, student_id: str) -> dict[str, Any]:
        student_id = normalize_student_id(student_id)
        record = self._metadata.get("students", {}).get(student_id)
        if record is None:
            raise StudentNotFoundError(f"student_id '{student_id}' was not found")
        return dict(record)

    def delete_student(self, student_id: str, upload_dir: Path | None = None) -> dict[str, Any]:
        """Delete one student's metadata and all related face files."""

        student_id = normalize_student_id(student_id)
        with self._lock:
            students = self._metadata.setdefault("students", {})
            record = students.get(student_id)
            if record is None:
                raise StudentNotFoundError(f"student_id '{student_id}' was not found")

            sample_records = record.get("samples", [])
            if not isinstance(sample_records, list):
                sample_records = []

            other_source_images = self._collect_other_source_images(exclude_student_id=student_id)
            deleted_embedding_files = self._delete_student_embedding_files(student_id, sample_records)
            deleted_image_files = self._delete_student_source_images(
                sample_records=sample_records,
                upload_dir=upload_dir,
                other_source_images=other_source_images,
            )

            students.pop(student_id, None)
            self._embedding_index.pop(student_id, None)
            self._metadata["version"] = METADATA_VERSION
            self._write_metadata(self._metadata)

            return {
                "student_id": student_id,
                "student_name": record.get("student_name"),
                "sample_count_deleted": len(sample_records),
                "deleted_embedding_files": deleted_embedding_files,
                "deleted_image_files": deleted_image_files,
                "remaining_student_count": len(students),
                "remaining_total_sample_count": self.total_sample_count,
            }

    def rename_student(
        self,
        old_student_id: str,
        new_student_id: str,
        student_name: str | None = None,
    ) -> dict[str, Any]:
        """Rename one enrolled student id while preserving all face samples."""

        old_student_id = normalize_student_id(old_student_id)
        new_student_id = normalize_student_id(new_student_id)
        clean_name = (student_name or "").strip()

        with self._lock:
            students = self._metadata.setdefault("students", {})
            record = students.get(old_student_id)
            if record is None:
                raise StudentNotFoundError(f"student_id '{old_student_id}' was not found")
            if old_student_id != new_student_id and new_student_id in students:
                raise DuplicateStudentError(f"student_id '{new_student_id}' already exists")

            sample_records = record.get("samples", [])
            if not isinstance(sample_records, list):
                sample_records = []

            moved_embedding_files = 0
            old_dir = (self.embedding_dir / old_student_id).resolve(strict=False)
            new_dir = (self.embedding_dir / new_student_id).resolve(strict=False)
            base_dir = self.embedding_dir.resolve(strict=False)
            if old_student_id != new_student_id and old_dir.exists():
                if not self._is_path_within(base_dir, old_dir) or not self._is_path_within(base_dir, new_dir):
                    raise RepositoryError("Unsafe embedding folder rename was blocked")
                if new_dir.exists():
                    raise DuplicateStudentError(f"embedding folder for '{new_student_id}' already exists")
                new_dir.parent.mkdir(parents=True, exist_ok=True)
                shutil.move(str(old_dir), str(new_dir))
                moved_embedding_files = sum(1 for path in new_dir.rglob("*") if path.is_file())

            for sample in sample_records:
                embedding_file = sample.get("embedding_file")
                if isinstance(embedding_file, str) and embedding_file.strip():
                    parts = [part for part in embedding_file.replace("\\", "/").split("/") if part]
                    if parts and parts[0] == old_student_id:
                        parts[0] = new_student_id
                        sample["embedding_file"] = "/".join(parts)

            now = _utc_now()
            record["student_id"] = new_student_id
            if clean_name:
                record["student_name"] = clean_name
            record["updated_at"] = now
            if old_student_id != new_student_id:
                students.pop(old_student_id, None)
                students[new_student_id] = record
                self._embedding_index[new_student_id] = self._embedding_index.pop(old_student_id, [])
                for entry in self._embedding_index.get(new_student_id, []):
                    sample = entry.get("sample")
                    if isinstance(sample, dict):
                        embedding_file = sample.get("embedding_file")
                        if isinstance(embedding_file, str) and embedding_file.strip():
                            parts = [part for part in embedding_file.replace("\\", "/").split("/") if part]
                            if parts and parts[0] == old_student_id:
                                parts[0] = new_student_id
                                sample["embedding_file"] = "/".join(parts)

            self._metadata["version"] = METADATA_VERSION
            self._write_metadata(self._metadata)

            return {
                "old_student_id": old_student_id,
                "new_student_id": new_student_id,
                "student": dict(record),
                "sample_count": len(sample_records),
                "moved_embedding_files": moved_embedding_files,
            }

    def get_loaded_samples(self, student_id: str) -> list[tuple[dict[str, Any], np.ndarray]]:
        """Return loaded sample embeddings for one student."""

        student_id = normalize_student_id(student_id)
        if student_id not in self._metadata.get("students", {}):
            raise StudentNotFoundError(f"student_id '{student_id}' was not found")

        entries = self._embedding_index.get(student_id, [])
        return [(dict(entry["sample"]), entry["embedding"]) for entry in entries]

    def list_enrolled(self) -> list[dict[str, Any]]:
        return [dict(record) for record in self._metadata.get("students", {}).values()]

    def iter_embeddings(self) -> list[tuple[dict[str, Any], dict[str, Any], np.ndarray]]:
        """Yield loaded embeddings as (student_record, sample_record, embedding)."""

        records: list[tuple[dict[str, Any], dict[str, Any], np.ndarray]] = []
        students = self._metadata.get("students", {})
        for student_id, entries in self._embedding_index.items():
            student = students.get(student_id)
            if student is None:
                continue
            for entry in entries:
                records.append((dict(student), dict(entry["sample"]), entry["embedding"]))
        return records

    def _read_metadata(self) -> tuple[dict[str, Any], bool]:
        try:
            with self.metadata_file.open("r", encoding="utf-8") as file:
                payload = json.load(file)
        except json.JSONDecodeError as exc:
            raise MetadataStorageError("Student metadata JSON is corrupted") from exc
        except OSError as exc:
            raise MetadataStorageError("Could not read student metadata") from exc

        if not isinstance(payload, dict) or not isinstance(payload.get("students"), dict):
            raise MetadataStorageError("Student metadata JSON has an invalid structure")

        return self._normalize_metadata(payload)

    def _normalize_metadata(self, payload: dict[str, Any]) -> tuple[dict[str, Any], bool]:
        """Normalize v1 or v2 metadata into the v2 multi-sample structure."""

        normalized: dict[str, Any] = {"version": METADATA_VERSION, "students": {}}
        migrated = payload.get("version") != METADATA_VERSION

        for raw_student_id, raw_record in payload.get("students", {}).items():
            if not isinstance(raw_record, dict):
                raise MetadataStorageError("Student metadata JSON has an invalid student record")

            student_id = normalize_student_id(str(raw_record.get("student_id") or raw_student_id))
            created_at = str(raw_record.get("created_at") or _utc_now())
            updated_at = str(raw_record.get("updated_at") or created_at)
            student_name = str(raw_record.get("student_name") or "").strip()
            if student_name == "":
                raise MetadataStorageError(f"student_name is missing for student_id '{student_id}'")

            samples, sample_migrated = self._normalize_samples(raw_record)
            migrated = migrated or sample_migrated
            normalized["students"][student_id] = {
                "student_id": student_id,
                "student_name": student_name,
                "created_at": created_at,
                "updated_at": updated_at,
                "samples": samples,
            }

        if normalized != payload:
            migrated = True
        return normalized, migrated

    def _normalize_samples(self, record: dict[str, Any]) -> tuple[list[dict[str, Any]], bool]:
        samples: list[dict[str, Any]] = []
        migrated = False

        raw_samples = record.get("samples")
        if isinstance(raw_samples, list):
            for raw_sample in raw_samples:
                if not isinstance(raw_sample, dict):
                    raise MetadataStorageError("Student sample metadata has an invalid structure")
                samples.append(self._normalize_sample_record(raw_sample))
            return samples, migrated

        legacy_embedding_file = record.get("embedding_file")
        if isinstance(legacy_embedding_file, str) and legacy_embedding_file.strip():
            migrated = True
            sample: dict[str, Any] = {
                "sample_id": "legacy",
                "embedding_file": legacy_embedding_file,
                "created_at": str(record.get("created_at") or _utc_now()),
                "migrated_from": "v1_single_embedding",
            }
            source_image = record.get("source_image")
            if isinstance(source_image, str) and source_image.strip():
                sample["source_image"] = source_image
            samples.append(sample)

        return samples, migrated

    def _normalize_sample_record(self, sample: dict[str, Any]) -> dict[str, Any]:
        sample_id = str(sample.get("sample_id") or "").strip()
        if not SAMPLE_ID_PATTERN.fullmatch(sample_id):
            raise MetadataStorageError(f"Invalid sample_id '{sample_id}' in metadata")

        embedding_file = sample.get("embedding_file")
        if not isinstance(embedding_file, str) or embedding_file.strip() == "":
            raise MetadataStorageError(f"Embedding filename is missing for sample '{sample_id}'")

        normalized: dict[str, Any] = {
            "sample_id": sample_id,
            "embedding_file": embedding_file,
            "created_at": str(sample.get("created_at") or _utc_now()),
        }
        for optional_key in ("sample_label", "source_image", "migrated_from"):
            value = sample.get(optional_key)
            if isinstance(value, str) and value.strip():
                normalized[optional_key] = value

        face = sample.get("face")
        if isinstance(face, dict):
            normalized["face"] = dict(face)

        return normalized

    def _build_sample_record(
        self,
        student_id: str,
        source_image: Path | None = None,
        face: dict[str, Any] | None = None,
        sample_label: str | None = None,
        created_at: str | None = None,
    ) -> dict[str, Any]:
        sample_id = self._new_sample_id()
        sample: dict[str, Any] = {
            "sample_id": sample_id,
            "embedding_file": f"{student_id}/{sample_id}.npy",
            "created_at": created_at or _utc_now(),
        }

        clean_label = (sample_label or "").strip()
        if clean_label:
            sample["sample_label"] = clean_label[:80]
        if source_image is not None:
            sample["source_image"] = str(Path(source_image))
        if face is not None:
            sample["face"] = dict(face)

        return sample

    def _write_metadata(self, metadata: dict[str, Any]) -> None:
        tmp_file = self.metadata_file.with_name(f"{self.metadata_file.name}.tmp")
        try:
            with tmp_file.open("w", encoding="utf-8") as file:
                json.dump(metadata, file, ensure_ascii=False, indent=2)
                file.write("\n")
            tmp_file.replace(self.metadata_file)
        except OSError as exc:
            raise MetadataStorageError("Could not write student metadata") from exc

    def _write_embedding(self, embedding_file: str, embedding: np.ndarray) -> None:
        final_path = self._embedding_path(embedding_file)
        final_path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = final_path.with_name(f".{final_path.name}.tmp.npy")
        vector = self._normalize_embedding(embedding)
        try:
            np.save(tmp_path, vector.astype(np.float32))
            tmp_path.replace(final_path)
        except OSError as exc:
            raise EmbeddingStorageError("Could not write embedding file") from exc

    def _read_embedding(self, sample: dict[str, Any]) -> np.ndarray:
        embedding_file = sample.get("embedding_file")
        if not isinstance(embedding_file, str) or embedding_file.strip() == "":
            raise EmbeddingStorageError("Embedding filename is missing from metadata")

        path = self._embedding_path(embedding_file)
        if not path.exists():
            raise EmbeddingStorageError(f"Embedding file '{embedding_file}' is missing")

        try:
            vector = np.load(path)
        except Exception as exc:
            raise EmbeddingStorageError(f"Embedding file '{embedding_file}' is corrupted") from exc

        return self._normalize_embedding(vector)

    def _embedding_path(self, embedding_file: str) -> Path:
        normalized = embedding_file.replace("\\", "/")
        parts = [part for part in normalized.split("/") if part]
        if not parts or any(part in {".", ".."} for part in parts):
            raise EmbeddingStorageError(f"Embedding file '{embedding_file}' is unsafe")
        return self.embedding_dir.joinpath(*parts)

    def _delete_student_embedding_files(
        self,
        student_id: str,
        sample_records: list[dict[str, Any]],
    ) -> int:
        deleted_count = 0
        deleted_paths: set[Path] = set()
        candidate_paths: set[Path] = set()

        for sample in sample_records:
            embedding_file = sample.get("embedding_file")
            if not isinstance(embedding_file, str) or embedding_file.strip() == "":
                continue
            try:
                candidate_paths.add(self._embedding_path(embedding_file).resolve(strict=False))
            except EmbeddingStorageError:
                continue

        # Legacy v1 fallback path that may still exist.
        candidate_paths.add((self.embedding_dir / f"{student_id}.npy").resolve(strict=False))

        for path in candidate_paths:
            if not self._is_path_within(self.embedding_dir.resolve(strict=False), path):
                continue
            if path.exists() and path.is_file():
                try:
                    path.unlink()
                except OSError as exc:
                    raise RepositoryError(f"Failed to delete embedding file '{path.name}'") from exc
                deleted_paths.add(path)
                deleted_count += 1

        student_dir = (self.embedding_dir / student_id).resolve(strict=False)
        if self._is_path_within(self.embedding_dir.resolve(strict=False), student_dir):
            if student_dir.exists() and student_dir.is_dir():
                extra_files = [path.resolve(strict=False) for path in student_dir.rglob("*") if path.is_file()]
                for path in extra_files:
                    if path not in deleted_paths:
                        deleted_count += 1
                try:
                    shutil.rmtree(student_dir)
                except OSError as exc:
                    raise RepositoryError(f"Failed to delete embedding folder for '{student_id}'") from exc

        return deleted_count

    def _delete_student_source_images(
        self,
        sample_records: list[dict[str, Any]],
        upload_dir: Path | None,
        other_source_images: set[str],
    ) -> int:
        if upload_dir is None:
            return 0

        base_upload_dir = Path(upload_dir).resolve(strict=False)
        deleted_count = 0
        handled_paths: set[str] = set()

        for sample in sample_records:
            source_image = sample.get("source_image")
            if not isinstance(source_image, str) or source_image.strip() == "":
                continue

            resolved = self._normalize_source_path(source_image)
            if resolved is None:
                continue
            normalized_key = str(resolved)

            if normalized_key in handled_paths:
                continue
            if normalized_key in other_source_images:
                continue
            if not self._is_path_within(base_upload_dir, resolved):
                continue
            if not resolved.exists() or not resolved.is_file():
                handled_paths.add(normalized_key)
                continue

            try:
                resolved.unlink()
            except OSError as exc:
                raise RepositoryError(f"Failed to delete source image '{resolved.name}'") from exc

            deleted_count += 1
            handled_paths.add(normalized_key)

        return deleted_count

    def _collect_other_source_images(self, exclude_student_id: str) -> set[str]:
        references: set[str] = set()
        for student_id, record in self._metadata.get("students", {}).items():
            if student_id == exclude_student_id:
                continue
            samples = record.get("samples", [])
            if not isinstance(samples, list):
                continue
            for sample in samples:
                if not isinstance(sample, dict):
                    continue
                source_image = sample.get("source_image")
                if not isinstance(source_image, str) or source_image.strip() == "":
                    continue
                resolved = self._normalize_source_path(source_image)
                if resolved is not None:
                    references.add(str(resolved))
        return references

    def _normalize_source_path(self, source_image: str) -> Path | None:
        path = Path(source_image)
        if not path.is_absolute():
            path = self.metadata_file.parent / path
        try:
            return path.resolve(strict=False)
        except OSError:
            return None

    @staticmethod
    def _is_path_within(base_dir: Path, candidate: Path) -> bool:
        try:
            candidate.relative_to(base_dir)
            return True
        except ValueError:
            return False

    @staticmethod
    def _normalize_embedding(embedding: np.ndarray) -> np.ndarray:
        vector = np.asarray(embedding, dtype=np.float32).reshape(-1)
        if vector.size == 0:
            raise EmbeddingStorageError("Embedding vector is empty")

        norm = np.linalg.norm(vector)
        if norm == 0.0:
            raise EmbeddingStorageError("Embedding vector has zero norm")

        return vector / norm

    @staticmethod
    def _new_sample_id() -> str:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
        return f"sample_{timestamp}_{uuid4().hex[:8]}"
