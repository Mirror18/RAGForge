#!/usr/bin/env python3
"""Measure real Ollama streaming TTFT, throughput, usage and wall time.

This probe is deliberately limited to a loopback Ollama endpoint. It sends one
public synthetic prompt, consumes the NDJSON stream, and persists only timing,
usage, model digest and hashes/lengths of generated content.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ENDPOINT = "http://127.0.0.1:11434/api/chat"
DEFAULT_OUTPUT = ROOT / "tests" / "evidence" / "phase6-real-ollama-stream-metrics.v1.json"
DEFAULT_PROMPT = (
    "Public synthetic benchmark. Answer exactly one short sentence: what is the "
    "purpose of a citation in a RAG answer?"
)


def _assert_local_only(endpoint: str, route: str) -> urllib.parse.ParseResult:
    if route != "LOCAL_ONLY":
        raise ValueError("this probe requires route=LOCAL_ONLY")
    parsed = urllib.parse.urlparse(endpoint)
    if parsed.scheme != "http" or parsed.port not in (None, 11434):
        raise ValueError("Ollama endpoint must be plain HTTP on the loopback default port")
    host = parsed.hostname
    if host in {"localhost", "127.0.0.1", "::1"}:
        return parsed
    try:
        addresses = {item[4][0] for item in socket.getaddrinfo(host, parsed.port or 11434, type=socket.SOCK_STREAM)}
    except socket.gaierror as exc:
        raise ValueError("Ollama endpoint hostname could not be resolved as loopback") from exc
    if not addresses or any(address not in {"127.0.0.1", "::1"} for address in addresses):
        raise ValueError("Ollama endpoint must resolve exclusively to loopback")
    return parsed


def _digest_text(value: str) -> str:
    return hashlib.sha256(value.encode("utf-8")).hexdigest()


def measure_stream(endpoint: str, model: str, prompt: str, timeout: int, *, route: str = "LOCAL_ONLY") -> dict[str, Any]:
    _assert_local_only(endpoint, route)
    payload = json.dumps({
        "model": model,
        "messages": [{"role": "user", "content": prompt}],
        "stream": True,
        "think": False,
        "options": {"temperature": 0},
    }).encode("utf-8")
    request = urllib.request.Request(
        endpoint,
        data=payload,
        method="POST",
        headers={"Accept": "application/x-ndjson", "Content-Type": "application/json"},
    )
    started_ns = time.perf_counter_ns()
    first_content_ns: int | None = None
    total_content = ""
    final: dict[str, Any] = {}
    chunk_count = 0
    with urllib.request.urlopen(request, timeout=timeout) as response:
        if response.status >= 400:
            raise RuntimeError(f"Ollama returned HTTP {response.status}")
        while True:
            line = response.readline()
            if not line:
                break
            line = line.strip()
            if not line:
                continue
            chunk = json.loads(line)
            chunk_count += 1
            message = chunk.get("message") or {}
            content = message.get("content") or ""
            if content and first_content_ns is None:
                first_content_ns = time.perf_counter_ns()
            total_content += content
            if chunk.get("done"):
                final = chunk
                break
    finished_ns = time.perf_counter_ns()
    if first_content_ns is None:
        raise RuntimeError("Ollama stream ended without a visible content token")
    usage = {
        "input_tokens": final.get("prompt_eval_count"),
        "output_tokens": final.get("eval_count"),
        "total_tokens": (final.get("prompt_eval_count") or 0) + (final.get("eval_count") or 0),
        "provider_usage_source": "PROVIDER_REPORTED" if final.get("eval_count") is not None else "UNAVAILABLE",
    }
    eval_duration_ns = final.get("eval_duration")
    output_tokens = usage["output_tokens"]
    tokens_per_second = None
    if isinstance(eval_duration_ns, (int, float)) and eval_duration_ns > 0 and isinstance(output_tokens, int):
        tokens_per_second = round(output_tokens / (eval_duration_ns / 1_000_000_000), 4)
    return {
        "status": "PASSED",
        "route": route,
        "endpoint": endpoint,
        "model": model,
        "stream": True,
        "chunk_count": chunk_count,
        "ttft_ms": round((first_content_ns - started_ns) / 1_000_000, 4),
        "stream_wall_time_ms": round((finished_ns - started_ns) / 1_000_000, 4),
        "provider_total_duration_ms": round(final["total_duration"] / 1_000_000, 4) if isinstance(final.get("total_duration"), (int, float)) else None,
        "prompt_eval_duration_ms": round(final["prompt_eval_duration"] / 1_000_000, 4) if isinstance(final.get("prompt_eval_duration"), (int, float)) else None,
        "eval_duration_ms": round(eval_duration_ns / 1_000_000, 4) if isinstance(eval_duration_ns, (int, float)) else None,
        "tokens_per_second": tokens_per_second,
        "usage": usage,
        "output": {"char_count": len(total_content), "sha256": _digest_text(total_content)},
        "raw_prompt_persisted": False,
        "raw_provider_body_persisted": False,
        "estimated_cost_usd": 0,
        "cost_basis": "Local Ollama has no provider price schedule; zero is an observed local estimate, not a production cost forecast.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", default=DEFAULT_ENDPOINT)
    parser.add_argument("--model", default="qwen3.5:9b")
    parser.add_argument("--route", default="LOCAL_ONLY")
    parser.add_argument("--prompt", default=DEFAULT_PROMPT, help="Synthetic prompt; not persisted in the report")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()
    report: dict[str, Any] = {
        "evidence_version": "phase6-real-ollama-stream-metrics.v1",
        "status": "BLOCKED",
        "executed_at_utc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "exact_command_redacted": "python scripts/phase6/ollama_stream_metrics.py [arguments redacted]",
        "fixture": {"classification": "Public synthetic", "prompt_sha256": _digest_text(args.prompt), "raw_prompt_persisted": False},
    }
    try:
        report["measurement"] = measure_stream(args.endpoint, args.model, args.prompt, args.timeout, route=args.route)
        report["status"] = "PASSED"
    except (OSError, urllib.error.URLError, urllib.error.HTTPError, ValueError, RuntimeError, json.JSONDecodeError) as exc:
        report["status"] = "BLOCKED"
        report["failure"] = {"type": type(exc).__name__, "message": str(exc)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if report["status"] == "PASSED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
