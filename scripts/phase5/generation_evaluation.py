#!/usr/bin/env python3
"""Deterministic Phase 5 generation/citation/abstention evaluation runner."""

from __future__ import annotations

import argparse
import hashlib
import json
import subprocess
from pathlib import Path
from statistics import median


ROOT = Path(__file__).resolve().parents[2]
DATASET = ROOT / "tests" / "evaluation" / "phase5-generation-dataset.v1.json"
OUTPUT = ROOT / "tests" / "evidence" / "phase5-generation-evaluation.json"
THRESHOLDS = {"citation_precision": 0.90, "faithfulness": 0.90, "abstention_accuracy": 0.90}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def git_revision() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()


def score(cases: list[dict], candidate_key: str) -> dict:
    citations = 0
    valid_citations = 0
    faithful_claims = 0
    claims = 0
    abstention_cases = [case for case in cases if case["answerability"] != "ANSWERABLE"]
    correct_abstentions = 0
    slices: dict[str, dict[str, int]] = {}
    for case in cases:
        output = case[candidate_key]
        expected_claims = {item["claim_id"]: set(item["supported_by"]) for item in case["claims"]}
        output_claims = {item["claim_id"]: item.get("citations", []) for item in output.get("claims", [])}
        case_citations = [citation for values in output_claims.values() for citation in values]
        citations += len(case_citations)
        valid_citations += sum(citation in set().union(*expected_claims.values()) if expected_claims else False
                               for citation in case_citations)
        for claim_id, supported in expected_claims.items():
            claims += 1
            cited = output_claims.get(claim_id, [])
            if cited and set(cited).issubset(supported):
                faithful_claims += 1
        if case["answerability"] != "ANSWERABLE" and output.get("abstained") is True:
            correct_abstentions += 1
        bucket = case["answerability"].lower()
        bucket_data = slices.setdefault(bucket, {"cases": 0, "claims": 0, "citations": 0, "valid_citations": 0})
        bucket_data["cases"] += 1
        bucket_data["claims"] += len(expected_claims)
        bucket_data["citations"] += len(case_citations)
        bucket_data["valid_citations"] += sum(citation in set().union(*expected_claims.values()) if expected_claims else False
                                               for citation in case_citations)
    return {
        "citation_precision": valid_citations / citations if citations else 0.0,
        "faithfulness": faithful_claims / claims if claims else 0.0,
        "abstention_accuracy": correct_abstentions / len(abstention_cases) if abstention_cases else 0.0,
        "counts": {"cases": len(cases), "claims": claims, "citations": citations,
                   "valid_citations": valid_citations, "correct_abstentions": correct_abstentions,
                   "abstention_cases": len(abstention_cases)},
        "slices": slices,
    }


def performance(cases: list[dict]) -> dict:
    return {
        "latency_ms": {"p50": median(case["latency_ms"] for case in cases),
                       "p95": sorted(case["latency_ms"] for case in cases)[max(0, int(len(cases) * .95) - 1)]},
        "ttft_ms": {"p50": median(case["ttft_ms"] for case in cases)},
        "tokens": {"input": sum(case["input_tokens"] for case in cases),
                    "output": sum(case["output_tokens"] for case in cases)},
        "estimated_cost": sum(case["estimated_cost"] for case in cases),
        "provider_calls": len(cases),
        "timeout_count": 0,
        "retry_count": 0,
        "degraded_count": 0,
        "cancel_count": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    dataset = json.loads(DATASET.read_text(encoding="utf-8"))
    candidate = score(dataset["cases"], "candidate")
    baseline = score(dataset["cases"], "baseline")
    passed = all(candidate[key] >= threshold for key, threshold in THRESHOLDS.items())
    report = {
        "evidence_version": "phase5-generation-evaluation-v1",
        "synthetic_only": True,
        "code_commit": git_revision(),
        "dataset_version": dataset["dataset_version"],
        "dataset_sha256": sha256(DATASET),
        "config": dataset["config"],
        "candidate": candidate,
        "baseline": baseline,
        "performance": performance(dataset["cases"]),
        "thresholds": THRESHOLDS,
        "passed": passed,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
