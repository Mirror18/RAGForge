#!/usr/bin/env python3
"""Measure bounded concurrent Ollama usage and local cost under LOCAL_ONLY.

The probe is intentionally limited to a loopback Ollama endpoint. It sends only
public synthetic prompts, records provider-reported usage and timing aggregates,
and never persists prompt, response, or provider body text.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import platform
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from typing import Any, Callable

from ollama_stream_metrics import _assert_local_only, measure_stream


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENDPOINT = "http://127.0.0.1:11434/api/chat"
DEFAULT_OUTPUT = ROOT / "tests" / "evidence" / "phase6-cost-local-ollama-concurrent.v1.json"
PROMPT_TEMPLATE = (
    "Public synthetic concurrent benchmark case {case}. Answer exactly one short sentence: "
    "why must a RAG answer retain provenance?"
)


def _sha256(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def _git_head() -> str:
    try:
        return subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
            capture_output=True, text=True, timeout=5,
        ).stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return "UNKNOWN"


def _percentile(values: list[float], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, int((percentile / 100) * len(ordered) + 0.999999) - 1))
    return round(ordered[index], 4)


class _ConcurrencyTracker:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._in_flight = 0
        self.max_in_flight = 0

    def call(self, operation: Callable[[], dict[str, Any]]) -> dict[str, Any]:
        with self._lock:
            self._in_flight += 1
            self.max_in_flight = max(self.max_in_flight, self._in_flight)
        try:
            return operation()
        finally:
            with self._lock:
                self._in_flight -= 1


def _measure_one(endpoint: str, model: str, case: int, timeout: int,
                 tracker: _ConcurrencyTracker, route: str, measurement_started_ns: int) -> dict[str, Any]:
    prompt = PROMPT_TEMPLATE.format(case=case)
    started = time.perf_counter_ns()
    try:
        measurement = tracker.call(lambda: measure_stream(endpoint, model, prompt, timeout, route=route))
        return {
            "case": case,
            "status": "PASSED",
            "prompt_sha256": _sha256(prompt),
            "ttft_ms": measurement["ttft_ms"],
            "stream_wall_time_ms": measurement["stream_wall_time_ms"],
            "provider_total_duration_ms": measurement["provider_total_duration_ms"],
            "tokens_per_second": measurement["tokens_per_second"],
            "usage": measurement["usage"],
            "output": measurement["output"],
            "started_offset_ms": round((started - measurement_started_ns) / 1_000_000, 4),
        }
    except Exception as exc:  # The report must not persist provider error bodies.
        return {
            "case": case,
            "status": "FAILED",
            "prompt_sha256": _sha256(prompt),
            "error_type": type(exc).__name__,
        }


def run_concurrent(endpoint: str, model: str, route: str, concurrency: int,
                   requests: int, timeout: int, warmup: int = 0) -> dict[str, Any]:
    _assert_local_only(endpoint, route)
    if concurrency < 1 or requests < 1 or timeout < 1 or warmup < 0:
        raise ValueError("concurrency, requests and timeout must be positive; warmup cannot be negative")

    for case in range(warmup):
        measure_stream(endpoint, model, PROMPT_TEMPLATE.format(case=f"warmup-{case}"), timeout, route=route)

    tracker = _ConcurrencyTracker()
    measurement_started_ns = time.perf_counter_ns()
    results: list[dict[str, Any]] = []
    with ThreadPoolExecutor(max_workers=concurrency, thread_name_prefix="ollama-p6") as pool:
        futures = [pool.submit(_measure_one, endpoint, model, case, timeout, tracker, route,
                               measurement_started_ns)
                   for case in range(requests)]
        for future in as_completed(futures):
            results.append(future.result())
    results.sort(key=lambda item: item["case"])

    successful = [item for item in results if item["status"] == "PASSED"]
    failed = [item for item in results if item["status"] != "PASSED"]
    ttft = [item["ttft_ms"] for item in successful]
    wall = [item["stream_wall_time_ms"] for item in successful]
    total_duration = [item["provider_total_duration_ms"] for item in successful
                      if item["provider_total_duration_ms"] is not None]
    input_tokens = sum(item["usage"].get("input_tokens") or 0 for item in successful)
    output_tokens = sum(item["usage"].get("output_tokens") or 0 for item in successful)
    total_tokens = sum(item["usage"].get("total_tokens") or 0 for item in successful)
    measured_calls = len(results)
    return {
        "status": "PASSED" if not failed else "FAILED",
        "route": route,
        "endpoint": endpoint,
        "model": model,
        "warmup_call_count": warmup,
        "requested_call_count": requests,
        "completed_call_count": len(successful),
        "failed_call_count": len(failed),
        "provider_call_count": measured_calls + warmup,
        "max_in_flight_observed": tracker.max_in_flight,
        "configured_concurrency": concurrency,
        "error_rate": round(len(failed) / measured_calls, 6),
        "latency_ms": {
            "ttft": {"p50": _percentile(ttft, 50), "p95": _percentile(ttft, 95), "p99": _percentile(ttft, 99)},
            "stream_wall_time": {"p50": _percentile(wall, 50), "p95": _percentile(wall, 95), "p99": _percentile(wall, 99)},
            "provider_total_duration": {"p50": _percentile(total_duration, 50), "p95": _percentile(total_duration, 95), "p99": _percentile(total_duration, 99)},
        },
        "usage": {
            "provider_usage_source": "PROVIDER_REPORTED" if successful else "UNAVAILABLE",
            "input_tokens": input_tokens,
            "output_tokens": output_tokens,
            "total_tokens": total_tokens,
            "retry_count": 0,
            "cancel_count": 0,
            "timeout_count": sum(1 for item in failed if item.get("error_type") == "TimeoutError"),
        },
        "cost": {
            "estimated_cost_usd": 0,
            "cost_basis": "Local Ollama has no provider price schedule; zero is an observed local estimate, not a production cost forecast.",
        },
        "cases": results,
        "raw_prompt_persisted": False,
        "raw_provider_body_persisted": False,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    parser.add_argument("--model", default="qwen3.5:9b")
    parser.add_argument("--model-digest", default=None)
    parser.add_argument("--route", default="LOCAL_ONLY")
    parser.add_argument("--concurrency", type=int, default=2)
    parser.add_argument("--requests", type=int, default=4)
    parser.add_argument("--warmup", type=int, default=1)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    report: dict[str, Any] = {
        "evidence_version": "phase6-cost-local-ollama-concurrent.v1",
        "status": "BLOCKED",
        "executed_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "code_commit": _git_head(),
        "config_version": "phase6-local-ollama-concurrent-cost-v1",
        "exact_command_redacted": "python scripts/phase6/ollama_concurrent_cost.py [arguments redacted]",
        "environment": {"platform": platform.platform(), "python": sys.version.split()[0]},
        "model_digest": args.model_digest,
        "fixture": {"classification": "Public synthetic", "prompt_template_sha256": _sha256(PROMPT_TEMPLATE),
                     "raw_prompt_persisted": False},
    }
    try:
        report["measurement"] = run_concurrent(args.endpoint, args.model, args.route, args.concurrency,
                                                args.requests, args.timeout, args.warmup)
        report["status"] = report["measurement"]["status"]
    except Exception as exc:
        report["status"] = "BLOCKED"
        report["failure"] = {"type": type(exc).__name__}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASSED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
