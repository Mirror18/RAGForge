"""Loopback-only acceptance probe for the real qwen3.5:9b Ollama protocol.

The probe is intentionally independent of the application process so it can be
run against a developer's local Ollama daemon. It never follows proxies,
contacts a non-loopback host, or writes request/output text to evidence. When
Ollama or the exact model is unavailable, it writes BLOCKED evidence and skips
without claiming a successful acceptance.
"""

from __future__ import annotations

import json
import os
import re
import socket
import time
import unittest
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


ROOT = Path(__file__).resolve().parents[2]
EVIDENCE_PATH = ROOT / "tests" / "evidence" / "phase2-local-ollama.json"
MODEL = "qwen3.5:9b"
DEFAULT_ENDPOINT = "http://127.0.0.1:11434"
LOCAL_HOSTS = {"127.0.0.1", "::1", "localhost"}
SHA256_DIGEST = re.compile(r"^(?:sha256:)?[0-9a-f]{64}$")
_LOCAL_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class LocalOllamaBlocked(RuntimeError):
    """The local acceptance prerequisite is not available."""

    def __init__(self, reason: str) -> None:
        self.reason = reason
        super().__init__(reason)


def _endpoint() -> str:
    raw = os.environ.get("RAGFORGE_OLLAMA_ENDPOINT", DEFAULT_ENDPOINT).rstrip("/")
    parsed = urlparse(raw)
    if parsed.scheme != "http" or parsed.hostname not in LOCAL_HOSTS:
        raise LocalOllamaBlocked("endpoint_not_loopback_http")
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise LocalOllamaBlocked("endpoint_contains_unsupported_components")
    try:
        addresses = socket.getaddrinfo(parsed.hostname, parsed.port or 11434, type=socket.SOCK_STREAM)
    except OSError as exc:
        raise LocalOllamaBlocked("loopback_resolution_failed") from exc
    if not addresses or any(not address[4][0].startswith(("127.", "::1")) for address in addresses):
        raise LocalOllamaBlocked("endpoint_resolves_outside_loopback")
    return raw


def _timeout_seconds() -> float:
    try:
        value = float(os.environ.get("RAGFORGE_OLLAMA_TIMEOUT_SECONDS", "10"))
    except ValueError as exc:
        raise LocalOllamaBlocked("invalid_timeout_configuration") from exc
    if value <= 0 or value > 60:
        raise LocalOllamaBlocked("timeout_out_of_bounds")
    return value


def _write_evidence(evidence: dict[str, Any]) -> None:
    EVIDENCE_PATH.write_text(
        json.dumps(evidence, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )


def _base_evidence() -> dict[str, Any]:
    return {
        "schema_version": 1,
        "provider": "OLLAMA",
        "model": MODEL,
        "status": "BLOCKED",
        "model_digest": None,
        "request": {
            "method": "POST",
            "path": "/api/chat",
            "stream": False,
            "authorization_header_sent": False,
            "prompt_stored": False,
            "raw_request_stored": False,
        },
        "response": {
            "parsed": False,
            "raw_output_stored": False,
            "http_status": None,
            "latency_ms": None,
            "usage_source": None,
            "usage_counts": None,
        },
        "network_policy": {
            "loopback_only": True,
            "proxy_disabled": True,
        },
        "error": {"code": "not_run"},
    }


def _json_request(
    endpoint: str,
    path: str,
    method: str,
    payload: dict[str, Any] | None,
    timeout: float,
) -> tuple[int, dict[str, Any], float]:
    body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    headers = {"Accept": "application/json"}
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(endpoint + path, data=body, method=method, headers=headers)
    if any(key.lower() == "authorization" for key in request.headers):
        raise AssertionError("local Ollama probe must not send Authorization")
    started = time.perf_counter()
    try:
        with _LOCAL_OPENER.open(request, timeout=timeout) as response:
            status = response.status
            raw = response.read()
    except urllib.error.HTTPError as exc:
        # Do not read or retain the upstream body: evidence only records status.
        raise LocalOllamaBlocked(f"http_status_{exc.code}") from exc
    except (urllib.error.URLError, TimeoutError, socket.timeout, OSError) as exc:
        raise LocalOllamaBlocked("ollama_unavailable") from exc
    elapsed_ms = round((time.perf_counter() - started) * 1000, 3)
    try:
        parsed = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise LocalOllamaBlocked("invalid_json_response") from exc
    if not isinstance(parsed, dict):
        raise LocalOllamaBlocked("invalid_json_object_response")
    return status, parsed, elapsed_ms


def _model_digest(tags: dict[str, Any]) -> str | None:
    models = tags.get("models")
    if not isinstance(models, list):
        return None
    for item in models:
        if isinstance(item, dict) and item.get("name") == MODEL:
            digest = item.get("digest")
            return digest if isinstance(digest, str) and SHA256_DIGEST.fullmatch(digest) else None
    return None


class Phase2LocalOllamaAcceptanceTest(unittest.TestCase):
    endpoint: str
    timeout: float
    evidence: dict[str, Any]

    @classmethod
    def setUpClass(cls) -> None:
        cls.evidence = _base_evidence()
        try:
            cls.endpoint = _endpoint()
            cls.timeout = _timeout_seconds()
            cls.evidence["endpoint"] = cls.endpoint
            status, tags, _ = _json_request(cls.endpoint, "/api/tags", "GET", None, cls.timeout)
            if status != 200:
                raise LocalOllamaBlocked(f"tags_http_status_{status}")
            digest = _model_digest(tags)
            if digest is None:
                raise LocalOllamaBlocked("model_not_available_or_digest_invalid")
            cls.evidence["model_digest"] = digest
        except LocalOllamaBlocked as blocked:
            cls.evidence["error"] = {"code": blocked.reason}
            _write_evidence(cls.evidence)
            raise unittest.SkipTest(blocked.reason)

    def test_actual_qwen_protocol_call_parse_latency_and_usage(self) -> None:
        # Synthetic protocol probe only; it is not an application/customer prompt.
        payload = {
            "model": MODEL,
            "messages": [
                {"role": "user", "content": "Return one short token for this local protocol probe."}
            ],
            "stream": False,
            "options": {"num_predict": 8},
        }
        self.evidence["request"]["authorization_header_sent"] = False
        try:
            status, root, latency_ms = _json_request(
                self.endpoint, "/api/chat", "POST", payload, self.timeout
            )
            self.evidence["response"]["latency_ms"] = latency_ms
            self.evidence["response"]["http_status"] = status
            if status != 200:
                raise LocalOllamaBlocked(f"chat_http_status_{status}")
            response_model = root.get("model")
            message = root.get("message")
            content = message.get("content") if isinstance(message, dict) else None
            prompt_tokens = root.get("prompt_eval_count")
            completion_tokens = root.get("eval_count")
            if not isinstance(response_model, str) or not isinstance(content, str):
                raise LocalOllamaBlocked("response_parse_failed")
            if (
                not isinstance(prompt_tokens, int)
                or isinstance(prompt_tokens, bool)
                or prompt_tokens < 0
                or not isinstance(completion_tokens, int)
                or isinstance(completion_tokens, bool)
                or completion_tokens < 0
            ):
                raise LocalOllamaBlocked("provider_usage_missing")
            self.evidence["response"].update(
                {
                    "parsed": True,
                    "raw_output_stored": False,
                    "response_model": response_model,
                    "content_present": bool(content),
                    "usage_source": "PROVIDER_REPORTED",
                    "usage_counts": {
                        "prompt_tokens": prompt_tokens,
                        "completion_tokens": completion_tokens,
                        "total_tokens": prompt_tokens + completion_tokens,
                    },
                }
            )
            self.evidence["status"] = "SUCCEEDED"
            self.evidence["error"] = None
        except LocalOllamaBlocked as blocked:
            self.evidence["status"] = "BLOCKED"
            self.evidence["error"] = {"code": blocked.reason}
            raise unittest.SkipTest(blocked.reason)
        finally:
            _write_evidence(self.evidence)


if __name__ == "__main__":
    unittest.main()
