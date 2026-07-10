"""Helpers for consistent face and integrated AI JSON responses."""

from __future__ import annotations

from typing import Any

from flask import jsonify


class ApiError(Exception):
    """Application error that can be rendered as a JSON response."""

    def __init__(
        self,
        message: str,
        status_code: int = 400,
        code: str = "BAD_REQUEST",
        details: Any | None = None,
    ) -> None:
        super().__init__(message)
        self.message = message
        self.status_code = status_code
        self.code = code
        self.details = details


def success_response(
    data: dict[str, Any] | list[Any] | None = None,
    message: str = "success",
    status_code: int = 200,
):
    payload: dict[str, Any] = {
        "success": True,
        "message": message,
        "data": data if data is not None else {},
    }
    return jsonify(payload), status_code


def error_response(
    message: str,
    status_code: int = 400,
    code: str = "BAD_REQUEST",
    details: Any | None = None,
):
    payload: dict[str, Any] = {
        "success": False,
        "error": {
            "code": code,
            "message": message,
        },
    }
    if details is not None:
        payload["error"]["details"] = details

    return jsonify(payload), status_code
