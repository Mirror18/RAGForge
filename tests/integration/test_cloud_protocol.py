"""Phase 2 cloud-provider protocol fixtures and executable acceptance tests.

The mock is deliberately local-only and uses only the Python standard library.
It is also imported by the performance and security tests so all three suites
exercise the same OpenAI-compatible request/response boundary.
"""

from __future__ import annotations

import hashlib
import json
import socket
import threading
import time
import unittest
import urllib.error
import urllib.request
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
TEST_SECRET = "fixture-secret-not-a-credential"
FULL_PROMPT = (
    "FULL_PROMPT_FIXTURE: this complete prompt must never appear in the "
    "provider evidence request body"
)
SUCCESS_RESPONSE = {
    "id": "chatcmpl-phase2-fixture",
    "model": "cloud-fixture-model",
    "choices": [
        {
            "index": 0,
            "message": {"role": "assistant", "content": "fixture answer"},
            "finish_reason": "stop",
        }
    ],
    "usage": {"prompt_tokens": 7, "completion_tokens": 5, "total_tokens": 12},
}


class ProviderCallError(RuntimeError):
    """Safe provider failure; the original body and prompt are never retained."""

    def __init__(self, error_class: str, status_code: int | None = None) -> None:
        self.error_class = error_class
        self.status_code = status_code
        suffix = f" status={status_code}" if status_code is not None else ""
        super().__init__(f"provider call classified as {error_class}{suffix}")


@dataclass(frozen=True)
class RequestIdentity:
    request_id: str
    correlation_id: str
    space_id: str
    invocation_id: str


@dataclass(frozen=True)
class ParsedProviderResponse:
    response_id: str
    model: str
    content: str
    usage: dict[str, int]


@dataclass(frozen=True)
class MockBehavior:
    kind: str
    status_code: int = 200
    body: str = ""
    delay_seconds: float = 0.0

    @classmethod
    def success(cls) -> "MockBehavior":
        return cls("success")

    @classmethod
    def http(cls, status_code: int, body: dict[str, Any] | None = None) -> "MockBehavior":
        return cls("http", status_code, json.dumps(body or {"error": {"code": "fixture"}}))

    @classmethod
    def invalid_response(cls) -> "MockBehavior":
        return cls("invalid", body=json.dumps({"choices": []}))

    @classmethod
    def timeout(cls, delay_seconds: float = 0.25) -> "MockBehavior":
        return cls("timeout", delay_seconds=delay_seconds)


class _MockProviderHandler(BaseHTTPRequestHandler):
    server: "MockOpenAIProvider"

    def log_message(self, format: str, *args: Any) -> None:
        # Test output must contain no prompt, credential, or request payload.
        return

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        self._write_json(404, {"error": {"code": "not_found"}})

    def do_POST(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler API
        length = int(self.headers.get("Content-Length", "0"))
        raw_body = self.rfile.read(length)
        request_id = self.headers.get("X-RAGForge-Request-Id", "")
        behavior = self.server.behavior_for(request_id)
        record = {
            "method": self.command,
            "path": self.path,
            "headers": {key.lower(): value for key, value in self.headers.items()},
            "body": raw_body.decode("utf-8", errors="replace"),
        }
        self.server.record(record)

        if self.path != "/v1/chat/completions":
            self._write_json(404, {"error": {"code": "not_found"}})
            return
        if behavior.kind == "timeout":
            time.sleep(behavior.delay_seconds)
            self._write_json(200, SUCCESS_RESPONSE)
            return
        if behavior.kind == "invalid":
            self._write_raw(200, behavior.body)
            return
        if behavior.kind == "http":
            self._write_raw(behavior.status_code, behavior.body)
            return
        self._write_json(200, SUCCESS_RESPONSE)

    def _write_json(self, status_code: int, body: dict[str, Any]) -> None:
        self._write_raw(status_code, json.dumps(body))

    def _write_raw(self, status_code: int, body: str | dict[str, Any]) -> None:
        payload = body if isinstance(body, str) else json.dumps(body)
        encoded = payload.encode("utf-8")
        try:
            self.send_response(status_code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            # Expected when the client classifies a delayed response as timeout.
            return


class MockOpenAIProvider(ThreadingHTTPServer):
    """Loopback-only, thread-per-request OpenAI-compatible provider."""

    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 64

    def __init__(self) -> None:
        super().__init__(("127.0.0.1", 0), _MockProviderHandler)
        self._behaviors: dict[str, MockBehavior] = {}
        self._records: list[dict[str, Any]] = []
        self._lock = threading.Lock()

    @property
    def endpoint(self) -> str:
        host, port = self.server_address
        return f"http://{host}:{port}"

    def set_behavior(self, request_id: str, behavior: MockBehavior) -> None:
        with self._lock:
            self._behaviors[request_id] = behavior

    def behavior_for(self, request_id: str) -> MockBehavior:
        with self._lock:
            return self._behaviors.get(request_id, MockBehavior.success())

    def record(self, request: dict[str, Any]) -> None:
        with self._lock:
            self._records.append(request)

    def records(self) -> list[dict[str, Any]]:
        with self._lock:
            return [dict(record, headers=dict(record["headers"])) for record in self._records]


def make_chain_identity(index: int) -> RequestIdentity:
    """Return deterministic UUIDv7-shaped, non-sensitive identities for evidence."""

    suffix = f"{index:012x}"

    def value(sequence: int) -> str:
        return f"0190f5c2-7c1e-{sequence + index:04x}-8def-{suffix}"

    return RequestIdentity(value(0x7000), value(0x7100), value(0x7200), value(0x7300))


def build_safe_probe_body(model: str, full_prompt: str) -> bytes:
    prompt_digest = hashlib.sha256(full_prompt.encode("utf-8")).hexdigest()
    body = {
        "model": model,
        "messages": [
            {"role": "system", "content": "RAGForge provider protocol probe"},
            {"role": "user", "content": f"[redacted-probe:{prompt_digest[:16]}]"},
        ],
        "stream": False,
        "max_tokens": 32,
    }
    return json.dumps(body, separators=(",", ":")).encode("utf-8")


def classify_http_status(status_code: int, response_body: str = "") -> str:
    lowered = response_body.lower()
    if status_code in (401, 403):
        return "AUTHENTICATION"
    if status_code in (408, 504):
        return "TIMEOUT"
    if status_code == 404 or "model_not_found" in lowered:
        return "MODEL_NOT_FOUND"
    if status_code == 429 and any(term in lowered for term in ("quota", "billing", "insufficient_quota")):
        return "QUOTA"
    if status_code == 429:
        return "RATE_LIMIT"
    if 500 <= status_code <= 599:
        return "UNAVAILABLE"
    return "INVALID_RESPONSE"


def parse_openai_response(raw_body: str) -> ParsedProviderResponse:
    try:
        root = json.loads(raw_body)
        choices = root["choices"]
        choice = choices[0]
        message = choice["message"]
        content = message["content"]
        usage = root["usage"]
        response_id = root["id"]
        model = root["model"]
        if not isinstance(choices, list) or not isinstance(content, str):
            raise ValueError("invalid choices")
        if not isinstance(response_id, str) or not isinstance(model, str):
            raise ValueError("invalid response identity")
        if not isinstance(usage, dict):
            raise ValueError("invalid usage")
        parsed_usage = {}
        for field in ("prompt_tokens", "completion_tokens", "total_tokens"):
            value = usage[field]
            if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                raise ValueError("invalid usage count")
            parsed_usage[field] = value
        return ParsedProviderResponse(response_id, model, content, parsed_usage)
    except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as exc:
        raise ProviderCallError("INVALID_RESPONSE") from exc


_LOCAL_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def call_openai_compatible(
    provider: MockOpenAIProvider,
    identity: RequestIdentity,
    *,
    full_prompt: str = FULL_PROMPT,
    secret: str = TEST_SECRET,
    timeout_seconds: float = 1.0,
) -> ParsedProviderResponse:
    body = build_safe_probe_body("cloud-fixture-model", full_prompt)
    request = urllib.request.Request(
        f"{provider.endpoint}/v1/chat/completions",
        data=body,
        method="POST",
        headers={
            "Accept": "application/json",
            "Content-Type": "application/json",
            "Authorization": f"Bearer {secret}",
            "X-RAGForge-Request-Id": identity.request_id,
            "X-RAGForge-Correlation-Id": identity.correlation_id,
            "X-RAGForge-Space-Id": identity.space_id,
            "Idempotency-Key": f"invoke-{identity.invocation_id}",
        },
    )
    try:
        with _LOCAL_OPENER.open(request, timeout=timeout_seconds) as response:
            raw_body = response.read().decode("utf-8")
        try:
            return parse_openai_response(raw_body)
        except ProviderCallError as exc:
            raise ProviderCallError(exc.error_class, 200) from exc
    except urllib.error.HTTPError as exc:
        response_body = exc.read().decode("utf-8", errors="replace")
        raise ProviderCallError(classify_http_status(exc.code, response_body), exc.code) from exc
    except (socket.timeout, TimeoutError) as exc:
        raise ProviderCallError("TIMEOUT") from exc
    except urllib.error.URLError as exc:
        if isinstance(exc.reason, (socket.timeout, TimeoutError)):
            raise ProviderCallError("TIMEOUT") from exc
        raise ProviderCallError("UNAVAILABLE") from exc


class Phase2CloudProtocolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.provider = MockOpenAIProvider()
        self.provider_thread = threading.Thread(target=self.provider.serve_forever, daemon=True)
        self.provider_thread.start()

    def tearDown(self) -> None:
        self.provider.shutdown()
        self.provider.server_close()
        self.provider_thread.join(timeout=2)

    def test_mock_is_loopback_and_openai_request_protocol_is_safe(self) -> None:
        identity = make_chain_identity(0)
        self.provider.set_behavior(identity.request_id, MockBehavior.success())
        response = call_openai_compatible(
            self.provider, identity, full_prompt=FULL_PROMPT, secret=TEST_SECRET
        )

        self.assertEqual(self.provider.server_address[0], "127.0.0.1")
        self.assertEqual(response.content, "fixture answer")
        self.assertEqual(response.usage["total_tokens"], 12)
        record = self.provider.records()[0]
        self.assertEqual(record["method"], "POST")
        self.assertEqual(record["path"], "/v1/chat/completions")
        self.assertEqual(record["headers"]["x-ragforge-request-id"], identity.request_id)
        self.assertEqual(record["headers"]["x-ragforge-correlation-id"], identity.correlation_id)
        self.assertEqual(record["headers"]["x-ragforge-space-id"], identity.space_id)
        self.assertEqual(record["headers"]["idempotency-key"], f"invoke-{identity.invocation_id}")
        self.assertEqual(record["headers"]["content-type"], "application/json")
        request_body = json.loads(record["body"])
        self.assertEqual(request_body["model"], "cloud-fixture-model")
        self.assertFalse(request_body["stream"])
        self.assertNotIn(TEST_SECRET, record["body"])
        self.assertNotIn(FULL_PROMPT, record["body"])

    def test_openai_response_parsing_fixture_preserves_usage(self) -> None:
        parsed = parse_openai_response(json.dumps(SUCCESS_RESPONSE))
        self.assertEqual(parsed.response_id, "chatcmpl-phase2-fixture")
        self.assertEqual(parsed.model, "cloud-fixture-model")
        self.assertEqual(parsed.content, "fixture answer")
        self.assertEqual(parsed.usage, {"prompt_tokens": 7, "completion_tokens": 5, "total_tokens": 12})

    def test_http_statuses_are_classified_without_body_leakage(self) -> None:
        fixtures = (
            (401, "AUTHENTICATION", {"error": {"message": "bad auth"}}),
            (404, "MODEL_NOT_FOUND", {"error": {"code": "model_not_found"}}),
            (429, "RATE_LIMIT", {"error": {"code": "rate_limit"}}),
            (500, "UNAVAILABLE", {"error": {"message": "upstream unavailable"}}),
        )
        for index, (status_code, expected, body) in enumerate(fixtures, start=1):
            identity = make_chain_identity(index)
            self.provider.set_behavior(identity.request_id, MockBehavior.http(status_code, body))
            with self.assertRaises(ProviderCallError) as raised:
                call_openai_compatible(self.provider, identity)
            self.assertEqual(raised.exception.error_class, expected)
            self.assertEqual(raised.exception.status_code, status_code)
            self.assertNotIn(TEST_SECRET, str(raised.exception))
            self.assertNotIn(FULL_PROMPT, str(raised.exception))

    def test_invalid_response_and_transport_timeout_are_classified(self) -> None:
        invalid_identity = make_chain_identity(5)
        self.provider.set_behavior(invalid_identity.request_id, MockBehavior.invalid_response())
        with self.assertRaises(ProviderCallError) as invalid:
            call_openai_compatible(self.provider, invalid_identity)
        self.assertEqual(invalid.exception.error_class, "INVALID_RESPONSE")
        self.assertEqual(invalid.exception.status_code, 200)

        timeout_identity = make_chain_identity(6)
        self.provider.set_behavior(timeout_identity.request_id, MockBehavior.timeout())
        with self.assertRaises(ProviderCallError) as timeout:
            call_openai_compatible(self.provider, timeout_identity, timeout_seconds=0.03)
        self.assertEqual(timeout.exception.error_class, "TIMEOUT")
        self.assertIsNone(timeout.exception.status_code)


if __name__ == "__main__":
    unittest.main()
