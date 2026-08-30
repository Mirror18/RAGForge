#!/usr/bin/env python3
"""Run the frozen Phase 6 RAG evaluation when a RAG-relevant path changes."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts" / "phase6" / "evaluation_runner.py"
DATASET = ROOT / "tests" / "evaluation" / "phase6-evaluation-dataset.v1.json"
CONFIG = ROOT / "tests" / "evaluation" / "phase6-evaluation-config.v1.json"
DEFAULT_OUTPUT = ROOT / "tests" / "evidence" / "phase7-evaluation-gate.v1.json"
EVIDENCE_VERSION = "phase7-evaluation-gate-v1"
EXPECTED_CASE_COUNT = 128
RAG_PATH_WORDS = {
    "retrieval",
    "answer",
    "prompt",
    "chunk",
    "embedding",
    "rerank",
    "parser",
}
EMPTY_TREE = "4b825dc642cb6eb9a060e54bf8d69288fbee4904"


def _path_words(path: str) -> list[str]:
    """Split path components and camel-case names into bounded words."""
    normalized = path.replace("\\", "/")
    words: list[str] = []
    for component in normalized.split("/"):
        words.extend(
            match.group(0).lower()
            for match in re.finditer(
                r"[A-Z]+(?=[A-Z][a-z]|\d|$)|[A-Z]?[a-z]+|\d+", component
            )
        )
    return words


def is_rag_path(path: str) -> bool:
    """Return whether a changed path contains a bounded RAG concern word."""
    return bool(RAG_PATH_WORDS.intersection(_path_words(path)))


def detect_rag_changes(changed_paths: Sequence[str]) -> list[str]:
    """Return changed paths that require the full offline evaluation."""
    return [path for path in changed_paths if is_rag_path(path)]


def _run(command: Sequence[str], *, capture_output: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        list(command),
        cwd=ROOT,
        text=True,
        capture_output=capture_output,
        check=False,
    )


def changed_paths(base_ref: str, head_ref: str) -> list[str]:
    """Read changed paths from git, handling the first push's all-zero base."""
    if not base_ref or not head_ref or base_ref.startswith("-") or head_ref.startswith("-"):
        raise ValueError("base-ref and head-ref must be non-empty git references")
    resolved_base = EMPTY_TREE if set(base_ref) == {"0"} else base_ref
    result = _run(["git", "diff", "--name-only", "--no-renames", resolved_base, head_ref, "--"])
    if result.returncode:
        detail = (result.stderr or result.stdout).strip().splitlines()
        raise RuntimeError(detail[-1] if detail else "git diff failed")
    return [line.strip() for line in result.stdout.splitlines() if line.strip()]


def _sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _base_report(base_ref: str, head_ref: str, paths: list[str], *, status: str, **extra: Any) -> dict[str, Any]:
    rag_paths = detect_rag_changes(paths)
    report: dict[str, Any] = {
        "evidence_version": EVIDENCE_VERSION,
        "synthetic_only": True,
        "cloud_egress": "disabled",
        "status": status,
        "passed": status == "skipped",
        "triggered": bool(rag_paths),
        "base_ref": base_ref,
        "head_ref": head_ref,
        "changed_files": paths,
        "rag_changed_files": rag_paths,
    }
    report.update(extra)
    return report


def _write_json(path: Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")


def _run_evaluation(output: Path) -> tuple[int, str]:
    """Invoke Phase 6 and discard the optional adapter outside the evidence tree."""
    with tempfile.TemporaryDirectory(prefix="ragforge-phase7-") as temp_dir:
        adapter = Path(temp_dir) / "promptfoo-adapter.json"
        result = _run(
            [
                sys.executable,
                str(RUNNER),
                "--dataset",
                str(DATASET),
                "--config",
                str(CONFIG),
                "--output",
                str(output),
                "--promptfoo-output",
                str(adapter),
            ]
        )
    detail = (result.stderr or result.stdout).strip().splitlines()
    return result.returncode, detail[-1] if detail else "Phase 6 runner produced no diagnostic"


def run_gate(base_ref: str, head_ref: str, output: Path) -> int:
    try:
        all_paths = changed_paths(base_ref, head_ref)
        rag_paths = detect_rag_changes(all_paths)
    except (OSError, ValueError, RuntimeError) as exc:
        report = _base_report(base_ref, head_ref, [], status="failed", failure=f"changed path detection failed: {exc}")
        _write_json(output, report)
        print(json.dumps(report, ensure_ascii=False))
        return 2

    if not rag_paths:
        report = _base_report(base_ref, head_ref, all_paths, status="skipped", reason="no RAG-relevant path changed")
        _write_json(output, report)
        print(json.dumps(report, ensure_ascii=False))
        return 0

    try:
        return_code, diagnostic = _run_evaluation(output)
    except (OSError, subprocess.SubprocessError) as exc:
        report = _base_report(
            base_ref,
            head_ref,
            all_paths,
            status="failed",
            failure=f"Phase 6 runner invocation failed: {exc}",
        )
        _write_json(output, report)
        print(json.dumps(report, ensure_ascii=False))
        return 1
    report: dict[str, Any]
    try:
        loaded = json.loads(output.read_text(encoding="utf-8"))
        report = loaded if isinstance(loaded, dict) else {}
    except (OSError, json.JSONDecodeError):
        report = {}

    report.update(
        {
            "evidence_version": EVIDENCE_VERSION,
            "synthetic_only": True,
            "cloud_egress": "disabled",
            "status": "passed" if return_code == 0 else "failed",
            "passed": return_code == 0 and report.get("passed") is True,
            "triggered": True,
            "base_ref": base_ref,
            "head_ref": head_ref,
            "changed_files": all_paths,
            "rag_changed_files": rag_paths,
            "gate": {
                "runner": str(RUNNER.relative_to(ROOT)).replace("\\", "/"),
                "dataset_path": str(DATASET.relative_to(ROOT)).replace("\\", "/"),
                "config_path": str(CONFIG.relative_to(ROOT)).replace("\\", "/"),
                "dataset_sha256": _sha256_file(DATASET),
                "config_sha256": _sha256_file(CONFIG),
                "expected_case_count": EXPECTED_CASE_COUNT,
                "runner_returncode": return_code,
                "diagnostic": diagnostic if return_code else None,
            },
        }
    )
    if return_code == 0:
        required = {"code_commit", "dataset_version", "dataset_sha256", "config", "run", "baseline", "candidate", "thresholds", "candidate"}
        missing = sorted(field for field in required if field not in report)
        if report.get("dataset_case_count") != EXPECTED_CASE_COUNT:
            missing.append("dataset_case_count=128")
        if report.get("config", {}).get("execution_policy", {}).get("cloud_egress") != "disabled":
            missing.append("cloud_egress=disabled")
        if missing:
            report["status"] = "failed"
            report["passed"] = False
            report["gate"]["validation_failures"] = missing
    _write_json(output, report)
    print(json.dumps({"status": report["status"], "passed": report["passed"], "output": str(output)}, ensure_ascii=False))
    return 0 if report["passed"] else 1


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-ref", required=True)
    parser.add_argument("--head-ref", required=True)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args(argv)
    return run_gate(args.base_ref, args.head_ref, args.output)


if __name__ == "__main__":
    raise SystemExit(main())
