from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


def utc_timestamp() -> str:
    return datetime.now(timezone.utc).isoformat()


class UniformEvaluationRepository:
    def __init__(self, evaluations_dir: Path, selections_dir: Path) -> None:
        self.evaluations_dir = Path(evaluations_dir)
        self.selections_dir = Path(selections_dir)
        self.evaluations_dir.mkdir(parents=True, exist_ok=True)
        self.selections_dir.mkdir(parents=True, exist_ok=True)

    def _evaluation_path(self, evaluation_id: str) -> Path:
        safe_id = self._safe_id(evaluation_id)
        return self.evaluations_dir / f"{safe_id}.json"

    def _selection_path(self, evaluation_id: str, selected_method: str) -> Path:
        safe_id = self._safe_id(evaluation_id)
        safe_method = self._safe_id(selected_method)
        return self.selections_dir / f"{safe_id}_{safe_method}.json"

    @staticmethod
    def _safe_id(value: str) -> str:
        cleaned = "".join(ch if ch.isalnum() or ch in {"_", "-"} else "_" for ch in str(value or "").strip())
        if not cleaned:
            raise ValueError("id must not be empty.")
        return cleaned[:160]

    @staticmethod
    def _write_json(path: Path, payload: dict[str, Any]) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp_path = path.with_name(f".{path.name}.tmp")
        with tmp_path.open("w", encoding="utf-8") as file:
            json.dump(payload, file, ensure_ascii=False, indent=2)
            file.write("\n")
        tmp_path.replace(path)

    @staticmethod
    def _read_json(path: Path) -> dict[str, Any]:
        with path.open("r", encoding="utf-8") as file:
            payload = json.load(file)
        if not isinstance(payload, dict):
            raise ValueError(f"Invalid evaluation JSON structure: {path}")
        return payload

    def save_evaluation(self, payload: dict[str, Any]) -> Path:
        evaluation_id = str(payload.get("evaluation_id") or "").strip()
        if not evaluation_id:
            raise ValueError("evaluation payload must include evaluation_id.")
        path = self._evaluation_path(evaluation_id)
        payload = dict(payload)
        payload["record_path"] = str(path)
        payload.setdefault("created_at", utc_timestamp())
        self._write_json(path, payload)
        return path

    def load_evaluation(self, evaluation_id: str) -> dict[str, Any]:
        path = self._evaluation_path(evaluation_id)
        if not path.exists():
            raise FileNotFoundError(f"Evaluation id was not found: {evaluation_id}")
        return self._read_json(path)

    def select_evaluation(self, evaluation_id: str, selected_method: str) -> dict[str, Any]:
        evaluation = self.load_evaluation(evaluation_id)
        candidates = evaluation.get("candidates", [])
        if not isinstance(candidates, list):
            raise ValueError("Evaluation record does not contain a candidate list.")

        selected = None
        for candidate in candidates:
            if isinstance(candidate, dict) and candidate.get("method") == selected_method:
                selected = candidate
                break
        if selected is None:
            available = [candidate.get("method") for candidate in candidates if isinstance(candidate, dict)]
            raise ValueError(f"selected_method must be one of: {available}")

        selection_record = {
            "saved": True,
            "evaluation_id": evaluation_id,
            "selected_method": selected_method,
            "selected_at": utc_timestamp(),
            "saved_pre_ai_image": evaluation.get("pre_ai_image"),
            "saved_pre_ai_image_url": evaluation.get("pre_ai_image_url"),
            "saved_processed_image": selected.get("processed_image"),
            "saved_processed_image_url": selected.get("processed_image_url"),
            "selected_candidate": selected,
            "comparison_candidates": candidates,
        }
        selection_path = self._selection_path(evaluation_id, selected_method)
        selection_record["selection_record_path"] = str(selection_path)
        self._write_json(selection_path, selection_record)

        evaluation["selected_method"] = selected_method
        evaluation["selected_candidate"] = selected
        evaluation["selection_record_path"] = str(selection_path)
        evaluation["updated_at"] = selection_record["selected_at"]
        self.save_evaluation(evaluation)
        return selection_record
