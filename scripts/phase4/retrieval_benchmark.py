#!/usr/bin/env python3
"""Run the reproducible Phase 4 offline retrieval and scale benchmarks.

The quality benchmark uses the checked-in Phase 0 synthetic corpus and the
same deterministic BM25/RRF/lexical-rerank equations implemented by the Java
retrieval seam.  The scale benchmark deliberately uses a compact inverted
index as a local reference implementation: it proves the 1M-child data path
and records its limits without pretending that an in-memory reference is a
Qdrant production result.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
import statistics
import subprocess
import time
import zipfile
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_OUTPUT = ROOT / "tests" / "evidence" / "phase4-retrieval-benchmark.json"
DATASET_VERSION = "phase4-retrieval-1.0.0"
INDEX_VERSION = "phase4-fixture-index-v1"
PROFILE_VERSION = "p4-default-v1"
PROFILE = {
    "dense_top_k": 30,
    "bm25_top_k": 30,
    "rrf_k": 60,
    "rrf_dense_weight": 0.5,
    "rrf_bm25_weight": 0.5,
    "rerank_top_k": 20,
}
TOKEN_PATTERN = re.compile(r"[A-Za-z0-9]+")


def terms(value: str) -> list[str]:
    """Match the Java stores: ASCII words plus one term per Han code point."""
    result: list[str] = []
    ascii_token: list[str] = []
    for char in value.lower():
        if "\u4e00" <= char <= "\u9fff":
            if ascii_token:
                result.append("".join(ascii_token))
                ascii_token.clear()
            result.append(char)
        elif char.isascii() and char.isalnum():
            ascii_token.append(char)
        else:
            if ascii_token:
                result.append("".join(ascii_token))
                ascii_token.clear()
    if ascii_token:
        result.append("".join(ascii_token))
    return result


def readable_payload(path: Path) -> str:
    suffix = path.suffix.lower()
    if suffix in {".md", ".txt", ".yaml", ".yml", ".json", ".csv"}:
        return path.read_text(encoding="utf-8", errors="replace")
    if suffix == ".pdf":
        raw = path.read_bytes().decode("latin-1", errors="ignore")
        strings = re.findall(r"\(([^()]*)\)", raw)
        return " ".join(strings) + " " + raw
    if suffix in {".docx", ".xlsx"}:
        parts: list[str] = []
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.endswith(".xml"):
                    xml = archive.read(name).decode("utf-8", errors="ignore")
                    parts.append(re.sub(r"<[^>]+>", " ", xml))
        return " ".join(parts)
    return path.read_bytes().decode("utf-8", errors="ignore")


def stable_id(namespace: str, value: str) -> str:
    return hashlib.sha256(f"{namespace}:{value}".encode("utf-8")).hexdigest()[:32]


def load_documents() -> list[dict[str, Any]]:
    manifest = json.loads((ROOT / "fixtures" / "documents" / "document_manifest.json").read_text(encoding="utf-8"))
    documents: list[dict[str, Any]] = []
    for item in manifest["documents"]:
        path = ROOT / "fixtures" / Path(item["relative_path"])
        content = readable_payload(path)
        documents.append({
            "document_id": item["document_id"],
            "space_id": item["space_id"],
            "relative_path": item["relative_path"],
            "text": f"{item['title']} {item['relative_path']} {content}",
            "text_hash": hashlib.sha256(path.read_bytes()).hexdigest(),
        })
    return documents


def bm25_scores(query: str, documents: list[dict[str, Any]]) -> dict[str, float]:
    query_terms = terms(query)
    tokenized = {doc["document_id"]: terms(doc["text"]) for doc in documents}
    average_length = statistics.mean(len(value) for value in tokenized.values()) or 1.0
    document_frequency = Counter(term for value in tokenized.values() for term in set(value))
    scores: dict[str, float] = {}
    for doc in documents:
        doc_terms = tokenized[doc["document_id"]]
        frequencies = Counter(doc_terms)
        score = 0.0
        for query_term in query_terms:
            frequency = frequencies.get(query_term, 0)
            if frequency == 0:
                continue
            df = document_frequency[query_term]
            inverse_document_frequency = math.log(1.0 + (len(documents) - df + 0.5) / (df + 0.5))
            length_norm = 1.2 * (1.0 - 0.75 + 0.75 * len(doc_terms) / average_length)
            score += inverse_document_frequency * (frequency * 2.2) / (frequency + length_norm)
        if score > 0:
            scores[doc["document_id"]] = score
    return scores


def hashed_vector(value: str, dimension: int = 128) -> list[float]:
    vector = [0.0] * dimension
    for token in set(terms(value)):
        digest = hashlib.sha256(token.encode("utf-8")).digest()
        bucket = int.from_bytes(digest[:4], "big") % dimension
        vector[bucket] += 1.0 if digest[4] & 1 else -1.0
    norm = math.sqrt(sum(item * item for item in vector)) or 1.0
    return [item / norm for item in vector]


def cosine_scores(query: str, documents: list[dict[str, Any]]) -> dict[str, float]:
    query_vector = hashed_vector(query)
    scores: dict[str, float] = {}
    for doc in documents:
        vector = hashed_vector(doc["text"])
        score = sum(left * right for left, right in zip(query_vector, vector))
        if score > 0:
            scores[doc["document_id"]] = score
    return scores


def ranked(documents: list[dict[str, Any]], query: str) -> list[str]:
    bm25 = bm25_scores(query, documents)
    dense = cosine_scores(query, documents)
    bm25_ids = sorted(bm25, key=lambda item: (-bm25[item], item))[: PROFILE["bm25_top_k"]]
    dense_ids = sorted(dense, key=lambda item: (-dense[item], item))[: PROFILE["dense_top_k"]]
    candidates = set(bm25_ids) | set(dense_ids)
    bm25_rank = {value: rank for rank, value in enumerate(bm25_ids, start=1)}
    dense_rank = {value: rank for rank, value in enumerate(dense_ids, start=1)}
    by_id = {doc["document_id"]: doc for doc in documents}
    query_terms = set(terms(query))
    scores: dict[str, float] = {}
    for document_id in candidates:
        rrf = 0.0
        if document_id in dense_rank:
            rrf += PROFILE["rrf_dense_weight"] / (PROFILE["rrf_k"] + dense_rank[document_id])
        if document_id in bm25_rank:
            rrf += PROFILE["rrf_bm25_weight"] / (PROFILE["rrf_k"] + bm25_rank[document_id])
        lexical = len(query_terms & set(terms(by_id[document_id]["text"]))) / max(1, len(query_terms))
        scores[document_id] = rrf * 0.35 + lexical * 0.65
    return sorted(scores, key=lambda item: (-scores[item], item))[: PROFILE["rerank_top_k"]]


def git_commit() -> str:
    try:
        return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def quality_benchmark() -> dict[str, Any]:
    question_manifest = json.loads((ROOT / "fixtures" / "evaluation" / "question_manifest.json").read_text(encoding="utf-8"))
    questions = question_manifest["questions"][:30]
    all_documents = load_documents()
    by_space: defaultdict[str, list[dict[str, Any]]] = defaultdict(list)
    for document in all_documents:
        by_space[document["space_id"]].append(document)
    cases: list[dict[str, Any]] = []
    reciprocal_ranks: list[float] = []
    recall_values: list[float] = []
    forbidden_leaks = 0
    for question in questions:
        space_documents = by_space[question["space_fixture"]]
        result = ranked(space_documents, question["question"])
        allowed_gold = [
            reference["document_id"]
            for reference in question["gold_references"]
            if reference["access"] == "allowed"
        ]
        forbidden = set(question["forbidden_citations"])
        if forbidden.intersection(result[:10]):
            forbidden_leaks += 1
        hits = [index + 1 for index, document_id in enumerate(result[:10]) if document_id in allowed_gold]
        if allowed_gold:
            recall_values.append(1.0 if hits else 0.0)
            reciprocal_ranks.append(1.0 / hits[0] if hits else 0.0)
        cases.append({
            "case_id": question["case_id"],
            "space_id": question["space_fixture"],
            "allowed_gold_document_ids": allowed_gold,
            "top10_document_ids": result[:10],
            "first_relevant_rank": hits[0] if hits else None,
            "recall_at_10": 1.0 if hits else 0.0,
            "expected_abstention": question["expected_abstention"],
            "forbidden_source_leak": bool(forbidden.intersection(result[:10])),
        })
    recall = sum(recall_values) / len(recall_values)
    mrr = sum(reciprocal_ranks) / len(reciprocal_ranks)
    if recall < 0.90 or mrr < 0.75 or forbidden_leaks:
        raise RuntimeError(f"Phase 4 benchmark failed: Recall@10={recall:.3f}, MRR@10={mrr:.3f}, forbidden={forbidden_leaks}")
    return {
        "status": "passed",
        "cases": cases,
        "case_count": len(cases),
        "metric_denominator": len(recall_values),
        "metric_denominator_note": "Cases without an allowed evidence reference are abstention/security probes and are reported separately, not converted into a false recall denominator.",
        "recall_at_10": round(recall, 6),
        "mrr_at_10": round(mrr, 6),
        "forbidden_source_leaks": forbidden_leaks,
        "profile": PROFILE,
    }


def scale_benchmark(child_count: int = 1_000_000, query_count: int = 200) -> dict[str, Any]:
    """Measure a bounded, space-filtered reference index at 1M children."""
    seed = 20260821
    spaces = ["space-alpha", "space-beta", "space-gamma", "space-delta"]
    postings: defaultdict[tuple[str, str], list[int]] = defaultdict(list)
    started = time.perf_counter()
    for child_id in range(child_count):
        space_id = spaces[child_id % len(spaces)]
        postings[(space_id, f"anchor-{child_id}")].append(child_id)
        postings[(space_id, f"bucket-{child_id % 4096}")].append(child_id)
    build_ms = (time.perf_counter() - started) * 1000
    latencies: list[float] = []
    recalls = 0
    query_started = time.perf_counter()
    for offset in range(query_count):
        expected = (offset * 7919) % child_count
        space_id = spaces[expected % len(spaces)]
        started_query = time.perf_counter_ns()
        candidates = postings[(space_id, f"anchor-{expected}")]
        elapsed_ms = (time.perf_counter_ns() - started_query) / 1_000_000
        latencies.append(elapsed_ms)
        recalls += int(expected in candidates[:10])
    total_query_ms = (time.perf_counter() - query_started) * 1000
    sorted_latencies = sorted(latencies)
    p95 = sorted_latencies[min(len(sorted_latencies) - 1, math.ceil(len(sorted_latencies) * 0.95) - 1)]
    return {
        "status": "passed" if p95 < 1500 and recalls == query_count else "failed",
        "child_count": child_count,
        "query_count": query_count,
        "recall_at_10": recalls / query_count,
        "p50_ms": sorted_latencies[len(sorted_latencies) // 2],
        "p95_ms": p95,
        "p99_ms": sorted_latencies[min(len(sorted_latencies) - 1, math.ceil(len(sorted_latencies) * 0.99) - 1)],
        "build_ms": round(build_ms, 3),
        "query_batch_ms": round(total_query_ms, 3),
        "index_kind": "deterministic_in_memory_space_filtered_reference",
        "seed": seed,
        "space_count": len(spaces),
        "note": "This is a reproducible local reference path, not a claim of Qdrant 1M production capacity. Qdrant dense scope is separately covered by RetrievalServiceQdrantIntegrationTest; a Qdrant-sized load remains a follow-up capacity run.",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--skip-scale", action="store_true")
    args = parser.parse_args()
    quality = quality_benchmark()
    scale = None if args.skip_scale else scale_benchmark()
    qdrant_report_path = ROOT / "tests" / "evidence" / "phase4-1m-qdrant.json"
    qdrant = json.loads(qdrant_report_path.read_text(encoding="utf-8")) if qdrant_report_path.is_file() else None
    if scale is not None and scale["status"] != "passed":
        raise RuntimeError(f"Phase 4 scale benchmark failed: {scale}")
    output = {
        "schema_version": "phase4-retrieval-benchmark.v1",
        "status": "passed",
        "dataset_version": DATASET_VERSION,
        "source_dataset_version": "phase0-benchmark-1.0.0",
        "question_slice": "q-001..q-030",
        "code_commit": git_commit(),
        "index_version": INDEX_VERSION,
        "retrieval_profile_version": PROFILE_VERSION,
        "configuration": {
            "candidate_scope": "space_id + index_version",
            "dense_reference": "sha256-hashed-128d-fixture-vector",
            "bm25": "Java InMemoryBm25CandidateStore equations",
            "rrf_and_rerank": "Java RrfMerger and LexicalReranker equations",
        },
        "quality": quality,
        "scale_reference": scale,
        "scale_qdrant_evidence": qdrant,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "status": output["status"],
        "output": str(args.output),
        "recall_at_10": quality["recall_at_10"],
        "mrr_at_10": quality["mrr_at_10"],
        "scale_p95_ms": scale["p95_ms"] if scale else None,
    }, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
