"""Face service storage setup helpers."""

from __future__ import annotations

import json
import shutil

from pathlib import Path
from typing import Any


def ensure_face_storage(config: Any) -> dict[str, Any]:
    """Create face runtime directories and migrate legacy storage if needed.

    The integrated server writes to root ``storage/`` by default. If the old
    standalone face service already has enrolled students and root metadata is
    still missing, copy the legacy storage tree once without deleting the old
    data.
    """

    storage_dir = Path(config.FACE_STORAGE_DIR)
    upload_dir = Path(config.FACE_UPLOAD_DIR)
    embedding_dir = Path(config.FACE_EMBEDDING_DIR)
    metadata_file = Path(config.FACE_METADATA_FILE)
    legacy_storage_dir = Path(config.FACE_LEGACY_STORAGE_DIR)
    legacy_metadata_file = legacy_storage_dir / "students.json"

    storage_dir.mkdir(parents=True, exist_ok=True)
    upload_dir.mkdir(parents=True, exist_ok=True)
    embedding_dir.mkdir(parents=True, exist_ok=True)
    metadata_file.parent.mkdir(parents=True, exist_ok=True)

    migrated = False
    migration_source = None
    if not metadata_file.exists() and legacy_metadata_file.exists():
        shutil.copytree(legacy_storage_dir, storage_dir, dirs_exist_ok=True)
        _rewrite_legacy_source_paths(metadata_file, legacy_storage_dir, storage_dir)
        migrated = True
        migration_source = str(legacy_storage_dir)
    elif metadata_file.exists():
        _rewrite_legacy_source_paths(metadata_file, legacy_storage_dir, storage_dir)

    return {
        "storage_dir": str(storage_dir),
        "metadata_file": str(metadata_file),
        "legacy_storage_dir": str(legacy_storage_dir),
        "migrated_from_legacy": migrated,
        "migration_source": migration_source,
    }


def _rewrite_legacy_source_paths(metadata_file: Path, legacy_storage_dir: Path, storage_dir: Path) -> None:
    try:
        with metadata_file.open("r", encoding="utf-8") as file:
            payload = json.load(file)
    except (OSError, json.JSONDecodeError):
        return

    changed = False
    legacy_storage_dir = legacy_storage_dir.resolve(strict=False)
    storage_dir = storage_dir.resolve(strict=False)
    students = payload.get("students", {})
    if not isinstance(students, dict):
        return

    for record in students.values():
        if not isinstance(record, dict):
            continue
        samples = record.get("samples", [])
        if not isinstance(samples, list):
            samples = [record]
        for sample in samples:
            if not isinstance(sample, dict):
                continue
            raw_source = sample.get("source_image")
            if not isinstance(raw_source, str) or not raw_source.strip():
                continue
            source_path = Path(raw_source)
            if not source_path.is_absolute():
                source_path = legacy_storage_dir / source_path
            resolved_source = source_path.resolve(strict=False)
            try:
                relative_source = resolved_source.relative_to(legacy_storage_dir)
            except ValueError:
                relative_source = _relative_storage_upload_path(resolved_source)
                if relative_source is None:
                    continue
            sample["source_image"] = str(storage_dir / relative_source)
            changed = True

    if not changed:
        return

    tmp_file = metadata_file.with_name(f"{metadata_file.name}.tmp")
    try:
        with tmp_file.open("w", encoding="utf-8") as file:
            json.dump(payload, file, ensure_ascii=False, indent=2)
            file.write("\n")
        tmp_file.replace(metadata_file)
    except OSError:
        try:
            tmp_file.unlink(missing_ok=True)
        except OSError:
            pass


def _relative_storage_upload_path(source_path: Path) -> Path | None:
    parts = source_path.parts
    lower_parts = [part.lower() for part in parts]
    for index, part in enumerate(lower_parts):
        if part != "storage":
            continue
        remaining = parts[index + 1 :]
        if not remaining or remaining[0].lower() != "uploads":
            continue
        return Path(*remaining)
    return None
