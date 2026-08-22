#!/usr/bin/env python3
"""Probe non-AI API and SSE first-event latency against an explicitly supplied server."""

from __future__ import annotations

import argparse
import json
import math
import os
import time
import urllib.error
import urllib.request
from pathlib import Path
from statistics import median
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "tests" / "evidence" / "phase6-capacity-online.v1.json"


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return round(ordered[min(len(ordered) - 1, max(0, math.ceil(len(ordered) * fraction) - 1))], 4)


def _read_first_sse_event(response: Any) -> bool:
    """Read one complete SSE event without waiting for the stream to close."""
    saw_content = False
    while True:
        line = response.readline()
        if not line:
            return False
        if line in (b"\n", b"\r\n"):
            if saw_content:
                return True
            continue
        if line.strip():
            saw_content = True


def probe(url: str, count: int, timeout: int, *, headers: dict[str, str] | None = None,
          first_event: bool = False) -> dict[str, Any]:
    values: list[float] = []
    errors = 0
    for _ in range(count):
        started = time.perf_counter_ns()
        try:
            request_headers = {"Accept": "text/event-stream" if first_event else "application/json"}
            request_headers.update(headers or {})
            request = urllib.request.Request(url, method="GET", headers=request_headers)
            with urllib.request.urlopen(request, timeout=timeout) as response:
                if response.status >= 500:
                    errors += 1
                    continue
                if first_event:
                    if not _read_first_sse_event(response):
                        errors += 1
                        continue
                else:
                    response.read(4096)
            values.append((time.perf_counter_ns() - started) / 1_000_000)
        except (OSError, urllib.error.URLError, urllib.error.HTTPError):
            errors += 1
    return {"url": url, "count": count, "errors": errors, "error_rate": errors / count, "p50_ms": percentile(values, .5), "p95_ms": percentile(values, .95), "p99_ms": percentile(values, .99)}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server-url", required=True)
    parser.add_argument("--non-ai-path", default="/actuator/health")
    parser.add_argument("--sse-url", help="An already-created run SSE URL; no run is created by this probe")
    parser.add_argument("--sse-cookie-env",
                        help="Environment variable containing the HttpOnly session cookie for the SSE request")
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--timeout", type=int, default=15)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    report: dict[str, Any] = {"evidence_version": "phase6-capacity-online.v1", "status": "BLOCKED", "thresholds": {"non_ai_api_p95_ms_lt": 300, "sse_first_event_p95_ms_lt": 500, "ttft_excluded": True}, "exact_command": " ".join(["python", "scripts/phase6/online_latency_probe.py", *(__import__("sys").argv[1:])])}
    try:
        report["non_ai_api"] = probe(args.server_url.rstrip("/") + args.non_ai_path, args.count, args.timeout)
        if not args.sse_url:
            report["sse_first_event"] = {"status": "BLOCKED", "reason": "sse_url_not_provided; a run must be created by an authenticated harness"}
        else:
            sse_headers: dict[str, str] = {}
            if args.sse_cookie_env:
                cookie = os.environ.get(args.sse_cookie_env)
                if not cookie:
                    report["sse_first_event"] = {
                        "status": "BLOCKED",
                        "reason": "sse_cookie_env_is_missing_or_empty",
                        "auth_source": "environment_variable",
                        "auth_env": args.sse_cookie_env,
                    }
                else:
                    sse_headers["Cookie"] = cookie
                    report["sse_first_event"] = probe(
                        args.sse_url, args.count, args.timeout, headers=sse_headers, first_event=True)
                    report["sse_first_event"]["auth_source"] = "environment_variable"
                    report["sse_first_event"]["auth_env"] = args.sse_cookie_env
            else:
                report["sse_first_event"] = probe(args.sse_url, args.count, args.timeout, first_event=True)
                report["sse_first_event"]["auth_source"] = "none"
        report["status"] = "PASSED" if report["non_ai_api"]["errors"] == 0 and report["non_ai_api"]["p95_ms"] < 300 and report["sse_first_event"].get("p95_ms") is not None and report["sse_first_event"]["p95_ms"] < 500 else "FAILED"
    except Exception as exc:  # noqa: BLE001 - preserve external service blocker
        report["status"] = "BLOCKED"
        report["failure"] = {"type": type(exc).__name__, "message": str(exc)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASSED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
