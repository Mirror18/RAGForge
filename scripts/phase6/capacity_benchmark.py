#!/usr/bin/env python3
"""Run the Phase 6 retrieval-capacity probe with a live Ollama dimension.

The embedding route is queried before Qdrant is created.  The 1M-point corpus
uses deterministic synthetic vectors *at that live dimension* so that the
capacity run is reproducible and contains no customer text.  The report never
labels those vectors as production embeddings.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import shlex
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from statistics import median
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "tests" / "evidence" / "phase6-capacity-retrieval.v1.json"
COMPOSE_FILE = ROOT / "deploy" / "compose" / "compose.yaml"
COMPOSE_PROJECT = "ragforge-p6-capacity-a2"
QDRANT_PORT = 26347
QDRANT_GRPC_PORT = 26348
COLLECTION = "ragforge_phase6_capacity_1m"
QDRANT_IMAGE = "qdrant/qdrant:v1.11.5"
LOCAL_QDRANT_AUTH_VALUE = "phase6-capacity-local-only"
OLLAMA_URL = "http://127.0.0.1:11434"
EMBEDDING_MODEL = "nomic-embed-text:latest"
CHILD_COUNT = 1_000_000
QUERY_COUNT = 400
CONCURRENCY = 20
SPACES = ("space-alpha", "space-beta", "space-gamma", "space-delta")
INDEX_VERSION = "phase6-capacity-v1"
CONFIG_VERSION = "phase6-capacity-config-v1"
DEFAULT_BATCH_SIZE = 256
UPLOAD_RETRIES = 6
UPLOAD_REQUEST_TIMEOUT = 900


class UploadError(RuntimeError):
    def __init__(self, cause: Exception, diagnostics: list[dict[str, Any]]) -> None:
        super().__init__(str(cause))
        self.cause = cause
        self.diagnostics = diagnostics


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def git_value(*args: str) -> str:
    try:
        return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def http_json(base_url: str, method: str, path: str, payload: dict[str, Any] | None = None, timeout: int = 120, headers: dict[str, str] | None = None) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(
        f"{base_url}{path}", data=data, method=method,
        headers={"Content-Type": "application/json", **(headers or {})},
    )
    with urllib.request.urlopen(request, timeout=timeout) as response:
        body = response.read().decode("utf-8")
        if not body.strip():
            return {}
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"body": body}


def qdrant_json(base_url: str, method: str, path: str, payload: dict[str, Any] | None = None, timeout: int = 120) -> dict[str, Any]:
    return http_json(base_url, method, path, payload, timeout, {"api-key": LOCAL_QDRANT_AUTH_VALUE})


def live_embedding_probe(ollama_url: str, model: str) -> dict[str, Any]:
    parsed = urllib.parse.urlparse(ollama_url)
    if parsed.scheme != "http" or parsed.hostname not in {"127.0.0.1", "localhost"}:
        raise ValueError("embedding probe only permits a loopback HTTP Ollama endpoint")
    response = http_json(ollama_url, "POST", "/api/embed", {"model": model, "input": "phase6 capacity dimension probe"}, timeout=180)
    embeddings = response.get("embeddings")
    if not isinstance(embeddings, list) or not embeddings or not isinstance(embeddings[0], list):
        raise ValueError("Ollama response did not contain embeddings")
    dimension = len(embeddings[0])
    if dimension < 16 or dimension == 8:
        raise ValueError(f"refusing proxy dimension {dimension}")
    return {
        "endpoint": ollama_url,
        "model": response.get("model", model),
        "dimension": dimension,
        "embedding_count": len(embeddings),
        "response_sha256": sha256_bytes(json.dumps(response, sort_keys=True).encode("utf-8")),
        "measurement": "live /api/embed response",
    }


def vector_for(child_id: int, dimension: int) -> list[float]:
    """Return a deterministic dense vector with the live route's dimension."""
    digest = hashlib.sha256(f"phase6-capacity:{child_id}".encode("ascii")).digest()
    values = [0.0] * dimension
    # Sixteen non-zero coordinates keep generation deterministic while the
    # serialized vector remains a dense vector of the measured dimension.
    for offset in range(16):
        raw = int.from_bytes(digest[(offset * 2) % len(digest):(offset * 2) % len(digest) + 2], "big")
        values[offset] = (raw / 65535.0) * 2.0 - 1.0
    norm = math.sqrt(sum(value * value for value in values)) or 1.0
    return [round(value / norm, 7) for value in values]


def compose_env() -> dict[str, str]:
    env = os.environ.copy()
    env.update({
        "COMPOSE_PROJECT_NAME": COMPOSE_PROJECT,
        "QDRANT_PORT": str(QDRANT_PORT),
        "QDRANT_GRPC_PORT": str(QDRANT_GRPC_PORT),
        "QDRANT_API_KEY": LOCAL_QDRANT_AUTH_VALUE,
        "RAGFORGE_VOLUME_PREFIX": COMPOSE_PROJECT,
        "RAGFORGE_NETWORK_NAME": f"{COMPOSE_PROJECT}-core",
    })
    return env


def compose(*args: str, check: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["docker", "compose", "-p", COMPOSE_PROJECT, "-f", str(COMPOSE_FILE), *args],
        cwd=ROOT, env=compose_env(), check=check, capture_output=True, text=True,
    )


def docker_qdrant_container_id() -> str | None:
    result = compose("ps", "-q", "qdrant", check=False)
    container_id = result.stdout.strip()
    return container_id or None


def resource_diagnostics() -> dict[str, Any]:
    """Capture bounded local diagnostics without exposing container env/secrets."""
    diagnostics: dict[str, Any] = {
        "captured_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "compose_ps": None,
        "docker_stats": None,
        "qdrant_state": None,
        "qdrant_logs_tail": None,
    }
    try:
        ps = compose("ps", "--all", check=False)
        diagnostics["compose_ps"] = {"returncode": ps.returncode, "stdout": ps.stdout[-4000:], "stderr": ps.stderr[-2000:]}
        container_id = docker_qdrant_container_id()
        if not container_id:
            return diagnostics
        state = subprocess.run(
            ["docker", "inspect", "--format", "{{json .State}}", container_id],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        diagnostics["qdrant_state"] = {"returncode": state.returncode, "stdout": state.stdout[-4000:], "stderr": state.stderr[-2000:]}
        stats = subprocess.run(
            ["docker", "stats", "--no-stream", "--format", "{{json .}}", container_id],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        diagnostics["docker_stats"] = {"returncode": stats.returncode, "stdout": stats.stdout[-4000:], "stderr": stats.stderr[-2000:]}
        logs = subprocess.run(
            ["docker", "logs", "--tail", "80", container_id],
            cwd=ROOT, capture_output=True, text=True, check=False,
        )
        diagnostics["qdrant_logs_tail"] = (logs.stdout + logs.stderr)[-12000:]
    except OSError as exc:
        diagnostics["diagnostic_error"] = {"type": type(exc).__name__, "message": str(exc)}
    return diagnostics


def wait_ready(base_url: str) -> None:
    deadline = time.time() + 180
    while time.time() < deadline:
        try:
            qdrant_json(base_url, "GET", "/readyz", timeout=5)
            return
        except (OSError, urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError):
            time.sleep(1)
    raise TimeoutError("Qdrant did not become ready within 180 seconds")


def create_collection(base_url: str, dimension: int) -> None:
    qdrant_json(base_url, "PUT", f"/collections/{COLLECTION}", {
        "vectors": {"size": dimension, "distance": "Cosine"},
        "hnsw_config": {"m": 16, "ef_construct": 100},
        "on_disk_payload": True,
    })
    for field in ("space_id", "index_version"):
        qdrant_json(base_url, "PUT", f"/collections/{COLLECTION}/index", {
            "field_name": field, "field_schema": "keyword",
        })


def upload(base_url: str, dimension: int, child_count: int, batch_size: int, max_retries: int, request_timeout: int) -> dict[str, Any]:
    started = time.perf_counter()
    batch_count = math.ceil(child_count / batch_size)
    retry_count = 0
    failed_batches: list[dict[str, Any]] = []
    for start in range(0, child_count, batch_size):
        points = [{
            "id": child_id,
            "vector": vector_for(child_id, dimension),
            "payload": {"space_id": SPACES[child_id % len(SPACES)], "index_version": INDEX_VERSION},
        } for child_id in range(start, min(start + batch_size, child_count))]
        for attempt in range(max_retries + 1):
            try:
                # Point IDs make retrying a timed-out request idempotent. A
                # server may have accepted the batch before the client timed
                # out, so the same payload is deliberately retried as-is.
                qdrant_json(base_url, "PUT", f"/collections/{COLLECTION}/points?wait=true", {"points": points}, timeout=request_timeout)
                break
            except (OSError, urllib.error.URLError, urllib.error.HTTPError) as exc:
                retry_count += 1
                diagnostic = {
                    "batch_start": start,
                    "batch_size": len(points),
                    "attempt": attempt + 1,
                    "max_attempts": max_retries + 1,
                    "type": type(exc).__name__,
                    "message": str(exc),
                }
                diagnostic["resource_diagnostics"] = resource_diagnostics()
                if len(failed_batches) < 12:
                    failed_batches.append(diagnostic)
                if attempt == max_retries:
                    raise UploadError(exc, failed_batches) from exc
                time.sleep(min(30, 2 ** attempt))
    return {
        "duration_seconds": round(time.perf_counter() - started, 4),
        "batch_size": batch_size,
        "batch_count": batch_count,
        "retry_count": retry_count,
        "failed_batches": failed_batches,
    }


def query_one(base_url: str, child_id: int, dimension: int, with_payload: bool) -> dict[str, Any]:
    space_id = SPACES[child_id % len(SPACES)]
    payload = {
        "vector": vector_for(child_id, dimension),
        "limit": 10,
        "with_payload": with_payload,
        "filter": {"must": [
            {"key": "space_id", "match": {"value": space_id}},
            {"key": "index_version", "match": {"value": INDEX_VERSION}},
        ]},
    }
    started = time.perf_counter_ns()
    try:
        result = qdrant_json(base_url, "POST", f"/collections/{COLLECTION}/points/search", payload, timeout=120)
        elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
        ids = {item.get("id") for item in result.get("result", [])}
        return {"latency_ms": elapsed_ms, "ok": child_id in ids, "error": None, "with_payload": with_payload}
    except Exception as exc:  # noqa: BLE001 - recorded as a measured request error
        return {"latency_ms": None, "ok": False, "error": type(exc).__name__, "with_payload": with_payload}


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(len(ordered) * fraction) - 1))
    return round(ordered[index], 4)


def run_queries(base_url: str, dimension: int, child_count: int, query_count: int, concurrency: int) -> dict[str, Any]:
    warmup = [query_one(base_url, (offset * 7919) % child_count, dimension, offset % 4 == 0) for offset in range(min(40, query_count))]
    if any(not item["ok"] for item in warmup):
        raise RuntimeError("warmup query did not return its target child")
    started = time.perf_counter()
    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=concurrency, thread_name_prefix="phase6-qdrant") as executor:
        futures = [executor.submit(query_one, base_url, (offset * 7919) % child_count, dimension, offset % 4 == 0)
                   for offset in range(query_count)]
        for future in as_completed(futures):
            results.append(future.result())
    elapsed = time.perf_counter() - started
    latencies = [item["latency_ms"] for item in results if item["latency_ms"] is not None]
    errors = [item for item in results if item["error"] is not None]
    return {
        "query_count": query_count,
        "concurrency": concurrency,
        "warmup_count": len(warmup),
        "duration_seconds": round(elapsed, 4),
        "throughput_qps": round(len(results) / elapsed, 4) if elapsed else None,
        "successful_requests": len(latencies),
        "error_count": len(errors),
        "error_rate": round(len(errors) / len(results), 6) if results else 1.0,
        "recall_at_10": round(sum(1 for item in results if item["ok"]) / len(results), 6) if results else 0.0,
        "p50_ms": percentile(latencies, 0.50),
        "p95_ms": percentile(latencies, 0.95),
        "p99_ms": percentile(latencies, 0.99),
        "mixed_load": {"filtered_spaces": list(SPACES), "payload_query_ratio": 0.25, "filter": "space_id + index_version"},
    }


def blocked_online_result(server_url: str | None) -> dict[str, Any]:
    reason = "server_url_not_provided" if not server_url else "not_executed_by_capacity_probe"
    return {
        "status": "BLOCKED",
        "threshold": {"non_ai_api_p95_ms_lt": 300, "sse_first_event_p95_ms_lt": 500, "ttft_excluded": True},
        "reason": reason,
        "repro_command": "python scripts/phase6/online_latency_probe.py --server-url http://127.0.0.1:8080 --output tests/evidence/phase6-capacity-online.v1.json",
        "server_url": server_url,
    }


def retry_command() -> str:
    command = [sys.executable, "scripts/phase6/capacity_benchmark.py", *sys.argv[1:]]
    return shlex.join(command)


def is_blocked_failure(exc: Exception) -> bool:
    cause = exc.cause if isinstance(exc, UploadError) else exc
    if isinstance(cause, (OSError, TimeoutError, urllib.error.URLError)):
        return True
    if isinstance(cause, urllib.error.HTTPError):
        return cause.code in {408, 425, 429} or cause.code >= 500
    return isinstance(exc, subprocess.CalledProcessError)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--child-count", type=int, default=CHILD_COUNT)
    parser.add_argument("--query-count", type=int, default=QUERY_COUNT)
    parser.add_argument("--concurrency", type=int, default=CONCURRENCY)
    parser.add_argument("--batch-size", type=int, default=DEFAULT_BATCH_SIZE)
    parser.add_argument("--upload-retries", type=int, default=UPLOAD_RETRIES)
    parser.add_argument("--upload-request-timeout", type=int, default=UPLOAD_REQUEST_TIMEOUT)
    parser.add_argument("--ollama-url", default=OLLAMA_URL)
    parser.add_argument("--embedding-model", default=EMBEDDING_MODEL)
    parser.add_argument("--server-url")
    parser.add_argument("--keep-stack", action="store_true")
    args = parser.parse_args()
    if args.child_count <= 0 or args.query_count <= 0 or args.concurrency <= 0 or args.batch_size <= 0 or args.upload_retries < 0 or args.upload_request_timeout <= 0:
        parser.error("child/query/concurrency counts must be positive")
    started_at = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
    report: dict[str, Any] = {
        "evidence_version": "phase6-capacity-retrieval.v1",
        "status": "BLOCKED",
        "started_at_utc": started_at,
        "code_commit": git_value("rev-parse", "HEAD"),
        "config_version": CONFIG_VERSION,
        "config_sha256": sha256_bytes(json.dumps({"child_count": args.child_count, "query_count": args.query_count, "concurrency": args.concurrency, "batch_size": args.batch_size, "upload_retries": args.upload_retries, "upload_request_timeout": args.upload_request_timeout, "collection": COLLECTION}, sort_keys=True).encode("utf-8")),
        "script_sha256": sha256_file(Path(__file__)),
        "environment": {"platform": platform.platform(), "machine": platform.machine(), "python": platform.python_version(), "docker_compose_project": COMPOSE_PROJECT, "qdrant_port": QDRANT_PORT, "qdrant_grpc_port": QDRANT_GRPC_PORT, "qdrant_image": QDRANT_IMAGE},
        "dataset": {"classification": "Public synthetic", "seed": "phase6-capacity:<child_id>", "child_count_requested": args.child_count, "vector_source": "deterministic synthetic vectors at live Ollama embedding dimension; not production embedding values", "space_count": len(SPACES)},
        "thresholds": {"retrieval_p95_ms_lt": 1500, "retrieval_recall_at_10_gte": 0.90, "non_ai_api_p95_ms_lt": 300, "sse_first_event_p95_ms_lt": 500},
        "online": blocked_online_result(args.server_url),
        "upload_configuration": {"batch_size": args.batch_size, "max_retries": args.upload_retries, "request_timeout_seconds": args.upload_request_timeout, "retry_backoff_seconds": [1, 2, 4, 8, 16, 30]},
    }
    base_url = f"http://127.0.0.1:{QDRANT_PORT}"
    phase = "embedding_probe"
    try:
        report["embedding_probe"] = live_embedding_probe(args.ollama_url, args.embedding_model)
        phase = "compose_start"
        compose("up", "-d", "qdrant")
        phase = "qdrant_ready"
        wait_ready(base_url)
        phase = "collection_create"
        create_collection(base_url, report["embedding_probe"]["dimension"])
        phase = "point_upload"
        report["upload"] = upload(base_url, report["embedding_probe"]["dimension"], args.child_count, args.batch_size, args.upload_retries, args.upload_request_timeout)
        report["upload_seconds"] = report["upload"]["duration_seconds"]
        phase = "mixed_retrieval"
        report["retrieval"] = run_queries(base_url, report["embedding_probe"]["dimension"], args.child_count, args.query_count, args.concurrency)
        retrieval = report["retrieval"]
        report["status"] = "PASSED" if args.child_count == CHILD_COUNT and retrieval["error_rate"] == 0 and retrieval["recall_at_10"] >= 0.90 and retrieval["p95_ms"] < 1500 else "FAILED"
    except Exception as exc:  # noqa: BLE001 - evidence must preserve blocked/failed cause
        report["status"] = "BLOCKED" if is_blocked_failure(exc) else "FAILED"
        cause = exc.cause if isinstance(exc, UploadError) else exc
        report["failure"] = {"phase": phase, "type": type(cause).__name__, "message": str(cause)}
        if isinstance(exc, UploadError):
            report["failure"]["upload_retry_diagnostics"] = exc.diagnostics
        report["failure"]["resource_diagnostics"] = resource_diagnostics()
        report["retry_command"] = retry_command()
    finally:
        try:
            qdrant_json(base_url, "DELETE", f"/collections/{COLLECTION}", timeout=30)
        except Exception:  # noqa: BLE001 - cleanup is best effort and recorded by Docker state
            pass
        if not args.keep_stack:
            compose("down", "-v", check=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    report.setdefault("retry_command", retry_command())
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASSED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
