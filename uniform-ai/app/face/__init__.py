"""Integrated face recognition package for the root AI server."""

from .config import ensure_face_storage
from .face_engine import FaceEngine
from .routes import face_bp
from .student_repository import StudentRepository

__all__ = ["FaceEngine", "StudentRepository", "ensure_face_storage", "face_bp"]
