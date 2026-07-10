from __future__ import annotations

from flask import jsonify


def json_response(payload: dict, status_code: int = 200):
    return jsonify(payload), status_code


def error_response(message: str, status_code: int = 400, details: dict | None = None):
    payload = {"error": message}
    if details:
        payload["details"] = details
    return jsonify(payload), status_code
