#!/usr/bin/env python3
"""Run a bounded, reproducible Qdrant 1M-child retrieval capacity probe.

This is intentionally opt-in: it starts an isolated local container, uploads
synthetic vectors only, measures filtered nearest-neighbour queries, writes a
small JSON report, and removes the container in a finally block.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import subprocess
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "tests" / "evidence" / "phase4-1m-qdrant.json"
IMAGE = "qdrant/qdrant:v1.11.5"
CONTAINER = "ragforge-p4-qdrant-1m"
PORT = 6337
COLLECTION = "ragforge_phase4_1m"
DIMENSION = 8
CHILD_COUNT = 1_000_000
QUERY_COUNT = 100
SPACES = ("space-alpha", "space-beta", "space-gamma", "space-delta")


def request(base_url: str, method: str, path: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
    req = urllib.request.Request(
        f"{base_url}{path}", data=data, method=method,
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=120) as response:
        body = response.read().decode("utf-8")
        if not body.strip():
            return {}
        try:
            return json.loads(body)
        except json.JSONDecodeError:
            return {"body": body}


def vector_for(child_id: int) -> list[float]:
    digest = hashlib.sha256(f"phase4-1m:{child_id}".encode("ascii")).digest()
    values = [((int.from_bytes(digest[offset:offset + 2], "big") / 65535.0) * 2.0) - 1.0
              for offset in range(0, DIMENSION * 2, 2)]
    norm = math.sqrt(sum(value * value for value in values)) or 1.0
    return [round(value / norm, 7) for value in values]


def docker(*args: str) -> str:
    result = subprocess.run(["docker", *args], check=True, capture_output=True, text=True)
    return result.stdout.strip()


def wait_ready(base_url: str) -> None:
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            request(base_url, "GET", "/readyz")
            return
        except (OSError, urllib.error.URLError, urllib.error.HTTPError):
            time.sleep(1)
    raise TimeoutError("Qdrant did not become ready within 120 seconds")


def upload(base_url: str, batch_size: int = 5_000) -> None:
    for start in range(0, CHILD_COUNT, batch_size):
        points = []
        for child_id in range(start, min(start + batch_size, CHILD_COUNT)):
            points.append({
                "id": child_id,
                "vector": vector_for(child_id),
                "payload": {
                    "space_id": SPACES[child_id % len(SPACES)],
                    "index_version": "phase4-1m-v1",
                },
            })
        request(base_url, "PUT", f"/collections/{COLLECTION}/points?wait=true", {"points": points})


def query(base_url: str, child_id: int) -> tuple[float, bool]:
    space_id = SPACES[child_id % len(SPACES)]
    started = time.perf_counter_ns()
    result = request(base_url, "POST", f"/collections/{COLLECTION}/points/search", {
        "vector": vector_for(child_id),
        "limit": 10,
        "with_payload": False,
        "filter": {
            "must": [
                {"key": "space_id", "match": {"value": space_id}},
                {"key": "index_version", "match": {"value": "phase4-1m-v1"}},
            ],
        },
    })
    elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000
    return elapsed_ms, any(item.get("id") == child_id for item in result.get("result", []))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--keep-container", action="store_true")
    args = parser.parse_args()
    base_url = f"http://127.0.0.1:{PORT}"
    report: dict[str, Any] = {
        "schema_version": "phase4-qdrant-1m.v1",
        "status": "failed",
        "image": IMAGE,
        "collection": COLLECTION,
        "child_count": CHILD_COUNT,
        "dimension": DIMENSION,
        "query_count": QUERY_COUNT,
        "seed": 20260821,
        "space_count": len(SPACES),
        "filter": "space_id + index_version",
        "index_version": "phase4-1m-v1",
        "query_endpoint": "/collections/{collection}/points/search",
        "container": CONTAINER,
    }
    try:
        docker("rm", "-f", CONTAINER)
    except subprocess.CalledProcessError:
        pass
    try:
        docker("run", "-d", "--name", CONTAINER, "-p", f"{PORT}:6333", IMAGE)
        wait_ready(base_url)
        request(base_url, "PUT", f"/collections/{COLLECTION}", {
            "vectors": {"size": DIMENSION, "distance": "Cosine"},
            "hnsw_config": {"m": 16, "ef_construct": 100},
            "on_disk_payload": True,
        })
        started = time.perf_counter()
        upload(base_url)
        report["upload_seconds"] = round(time.perf_counter() - started, 3)
        for child_id in range(0, QUERY_COUNT):
            query(base_url, child_id * 7919 % CHILD_COUNT)
        latencies: list[float] = []
        hits = 0
        query_started = time.perf_counter()
        for offset in range(QUERY_COUNT):
            elapsed_ms, hit = query(base_url, (offset * 7919) % CHILD_COUNT)
            latencies.append(elapsed_ms)
            hits += int(hit)
        sorted_latencies = sorted(latencies)
        p95_index = min(len(sorted_latencies) - 1, math.ceil(len(sorted_latencies) * 0.95) - 1)
        p99_index = min(len(sorted_latencies) - 1, math.ceil(len(sorted_latencies) * 0.99) - 1)
        report.update({
            "status": "passed" if hits == QUERY_COUNT and sorted_latencies[p95_index] < 1500 else "failed",
            "recall_at_10": hits / QUERY_COUNT,
            "p50_ms": sorted_latencies[len(sorted_latencies) // 2],
            "p95_ms": sorted_latencies[p95_index],
            "p99_ms": sorted_latencies[p99_index],
            "query_batch_seconds": round(time.perf_counter() - query_started, 3),
            "host_note": "Local Docker Desktop; exact CPU/RAM at run time is recorded by the operator environment and is not a production SLO claim.",
        })
        if report["status"] != "passed":
            raise RuntimeError(f"Qdrant 1M benchmark failed: {report}")
    finally:
        try:
            request(base_url, "DELETE", f"/collections/{COLLECTION}")
        except (OSError, urllib.error.URLError, urllib.error.HTTPError):
            pass
        if not args.keep_container:
            subprocess.run(["docker", "rm", "-f", CONTAINER], check=False, capture_output=True, text=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
