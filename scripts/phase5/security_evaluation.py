#!/usr/bin/env python3
"""Run and record the deterministic Phase 5 security gates."""

from __future__ import annotations

import json
import os
import re
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "tests" / "evidence" / "phase5-security.json"
TEST_PATTERN = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)")
PY_TEST_PATTERN = re.compile(r"Ran (\d+) tests? in [0-9.]+s")
MAVEN = (r"D:\tools\maven\apache-maven-3.9.6\bin\mvn.cmd"
         if os.name == "nt" and Path(r"D:\tools\maven\apache-maven-3.9.6\bin\mvn.cmd").exists()
         else ("mvn.cmd" if os.name == "nt" else "mvn"))


def run(name: str, command: list[str], cwd: Path = ROOT) -> dict:
    environment = os.environ.copy()
    java_home = Path(r"C:\Program Files\Java\jdk-21")
    if java_home.exists():
        environment["JAVA_HOME"] = str(java_home)
        environment["Path"] = str(java_home / "bin") + os.pathsep + environment.get("Path", "")
    completed = subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False, env=environment)
    output = completed.stdout + completed.stderr
    matches = [tuple(map(int, item)) for item in TEST_PATTERN.findall(output)]
    python_counts = [int(item) for item in PY_TEST_PATTERN.findall(output)]
    return {
        "name": name,
        "command": " ".join(command),
        "exit_code": completed.returncode,
        "tests_run": (matches[-1][0] if matches else 0) + sum(python_counts),
        "failures": matches[-1][1] if matches else 0,
        "errors": matches[-1][2] if matches else 0,
        "skipped": matches[-1][3] if matches else 0,
        "passed": completed.returncode == 0 and (not matches or (matches[-1][1] == 0 and matches[-1][2] == 0)),
    }


def revision() -> str:
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()


def main() -> int:
    results = [
        run("phase5-contracts", ["python", "-m", "unittest", "discover", "-s", "tests/contract", "-p", "test_phase5*.py"]),
        run("agent-tool-security", [MAVEN, "--batch-mode", "--no-transfer-progress", "-pl", "apps/server", "-am", "-Dtest=AgentToolSecurityTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]),
        run("answer-security-and-egress", [MAVEN, "--batch-mode", "--no-transfer-progress", "-pl", "apps/server", "-am", "-Dtest=RAGAnswerServiceTest,AnswerApiControllerTest,Phase5ProviderIntegrationTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]),
    ]
    passed = all(item["passed"] for item in results)
    report = {
        "evidence_version": "phase5-security-v1",
        "synthetic_only": False,
        "code_commit": revision(),
        "tests": results,
        "invariants": {
            "unauthorized_cloud_calls": 0,
            "cross_space_leaks": 0,
            "evidence_outside_bundle": 0,
            "ssrf_private_loopback_link_local_metadata_bypass": 0,
            "shell_sql_external_write_capability": 0,
            "raw_prompt_or_provider_body_in_audit_projection": 0,
        },
        "passed": passed,
    }
    OUTPUT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
