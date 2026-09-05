"""Bounded, local-only rerank HTTP seam for RAGForge.

The default implementation is deterministic and dependency-free. A model-backed
implementation can replace ``RerankEngine.score`` without changing the wire
contract or granting this process network/file-system access.
"""

from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from uuid import UUID

MAX_BODY_BYTES = 128 * 1024
MAX_CANDIDATES = 100
MAX_QUERY_CHARS = 2_000
MAX_CANDIDATE_TEXT_CHARS = 2_048
MAX_TOTAL_TEXT_CHARS = 100_000
MAX_MODEL_CHARS = 200


class RerankValidationError(ValueError):
    """Raised when a request violates the local runtime protocol bounds."""


@dataclass(frozen=True)
class RerankCandidate:
    space_id: UUID
    candidate_id: UUID
    text: str


class RerankEngine:
    """Stable baseline scorer with a model-compatible seam."""

    _term = re.compile(r"[\w]+", re.UNICODE)

    def score(self, query: str, candidate: RerankCandidate) -> float:
        query_terms = set(self._term.findall(query.casefold()))
        candidate_terms = set(self._term.findall(candidate.text.casefold()))
        if not query_terms:
            return 0.0
        return len(query_terms & candidate_terms) / len(query_terms)


class RerankService:
    def __init__(self, engine: RerankEngine | None = None) -> None:
        self.engine = engine or RerankEngine()

    def rerank(self, payload: Any) -> dict[str, Any]:
        if not isinstance(payload, dict):
            raise RerankValidationError("request must be an object")
        space_id = self._uuid(payload.get("space_id"), "space_id")
        model = self._bounded_string(payload.get("model"), "model", MAX_MODEL_CHARS)
        query = self._bounded_string(payload.get("query"), "query", MAX_QUERY_CHARS)
        top_k = payload.get("top_k")
        if not isinstance(top_k, int) or isinstance(top_k, bool) or not 1 <= top_k <= MAX_CANDIDATES:
            raise RerankValidationError("top_k is outside its bound")
        raw_candidates = payload.get("candidates")
        if not isinstance(raw_candidates, list) or not 1 <= len(raw_candidates) <= MAX_CANDIDATES:
            raise RerankValidationError("candidate count is outside its bound")
        candidates: list[RerankCandidate] = []
        identities: set[UUID] = set()
        total_chars = 0
        for raw in raw_candidates:
            if not isinstance(raw, dict):
                raise RerankValidationError("candidate must be an object")
            candidate_space = self._uuid(raw.get("space_id"), "candidate space_id")
            candidate_id = self._uuid(raw.get("candidate_id"), "candidate_id")
            text = self._bounded_string(raw.get("text"), "candidate text", MAX_CANDIDATE_TEXT_CHARS)
            if candidate_space != space_id or candidate_id in identities:
                raise RerankValidationError("candidate crosses space or identity boundary")
            identities.add(candidate_id)
            total_chars += len(text)
            candidates.append(RerankCandidate(candidate_space, candidate_id, text))
        if total_chars > MAX_TOTAL_TEXT_CHARS or top_k > len(candidates):
            raise RerankValidationError("candidate text or top_k exceeds its bound")
        scored = sorted(
            ((self.engine.score(query, candidate), candidate.candidate_id) for candidate in candidates),
            key=lambda item: (-item[0], str(item[1])),
        )[:top_k]
        return {
            "model": model,
            "capabilities": ["RERANK"],
            "results": [
                {"candidate_id": str(candidate_id), "score": float(score)}
                for score, candidate_id in scored
                if math.isfinite(score)
            ],
        }

    @staticmethod
    def _bounded_string(value: Any, field: str, maximum: int) -> str:
        if not isinstance(value, str) or not value.strip() or len(value) > maximum:
            raise RerankValidationError(f"{field} is outside its bound")
        return value

    @staticmethod
    def _uuid(value: Any, field: str) -> UUID:
        if not isinstance(value, str):
            raise RerankValidationError(f"{field} is invalid")
        try:
            return UUID(value)
        except ValueError as exc:
            raise RerankValidationError(f"{field} is invalid") from exc


class RerankRequestHandler(BaseHTTPRequestHandler):
    service = RerankService()

    def do_POST(self) -> None:  # noqa: N802 - stdlib handler API
        if self.path not in ("/v1/rerank", "/rerank"):
            self._write(HTTPStatus.NOT_FOUND, {"error": "not_found"})
            return
        raw_length = self.headers.get("Content-Length")
        try:
            length = int(raw_length or "-1")
        except ValueError:
            length = -1
        if length < 0 or length > MAX_BODY_BYTES:
            self._write(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": "request_too_large"})
            return
        try:
            payload = json.loads(self.rfile.read(length))
            response = self.service.rerank(payload)
        except (json.JSONDecodeError, UnicodeDecodeError, RerankValidationError):
            self._write(HTTPStatus.UNPROCESSABLE_ENTITY, {"error": "invalid_rerank_request"})
            return
        self._write(HTTPStatus.OK, response)

    def _write(self, status: HTTPStatus, payload: dict[str, Any]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format: str, *args: object) -> None:
        return


def serve(host: str = "127.0.0.1", port: int = 8090) -> None:
    """Serve only on the caller-selected local interface."""
    with ThreadingHTTPServer((host, port), RerankRequestHandler) as server:
        server.serve_forever()
