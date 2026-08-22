#!/usr/bin/env python3
"""Run Phase 6 security gates and write redacted, reproducible evidence.

The runner never contacts a provider or external service. Existing tests use
loopback-only synthetic servers. Missing SCA/SBOM tooling is recorded as
``blocked`` and makes the report fail until CI supplies the required tool.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "tests" / "evidence" / "phase6-security.v1.json"
MAVEN = Path(r"D:\tools\maven\apache-maven-3.9.6\bin\mvn.cmd")
LICENSE_REGISTER = ROOT / "docs" / "07-research" / "UPSTREAM_REUSE_REGISTER.md"
THIRD_PARTY_NOTICES = ROOT / "THIRD_PARTY_NOTICES.md"


def run(name: str, command: list[str], *, blocked_if_missing: bool = False) -> dict[str, Any]:
    executable = shutil.which(command[0]) or (command[0] if Path(command[0]).exists() else None)
    if executable is None:
        return {
            "name": name,
            "command": " ".join(command),
            "status": "blocked" if blocked_if_missing else "failed",
            "exit_code": None,
            "reason": f"required executable is unavailable: {command[0]}",
        }
    environment = os.environ.copy()
    java_home = Path(r"C:\Program Files\Java\jdk-21")
    if java_home.exists():
        environment["JAVA_HOME"] = str(java_home)
        environment["Path"] = str(java_home / "bin") + os.pathsep + environment.get("Path", "")
    # Windows can expose PATH under either casing when a child process is
    # launched from PowerShell.  Keep the installed SBOM scanner directory in
    # both forms so the nested required scan sees the same tool availability
    # as this runner.
    scanner_dirs = {
        str(Path(tool).resolve().parent)
        for tool in (shutil.which("syft"), shutil.which("trivy"))
        if tool
    }
    if scanner_dirs:
        child_path = os.pathsep.join((*scanner_dirs, environment.get("Path", "")))
        environment["Path"] = child_path
        environment["PATH"] = child_path
    completed = subprocess.run(
        command,
        cwd=ROOT,
        text=True,
        encoding="utf-8",
        errors="replace",
        capture_output=True,
        check=False,
        env=environment,
    )
    output = (completed.stdout or "") + (completed.stderr or "")
    return {
        "name": name,
        "command": " ".join(command),
        "status": "passed" if completed.returncode == 0 else "failed",
        "exit_code": completed.returncode,
        "output_sha256": __import__("hashlib").sha256(output.encode("utf-8")).hexdigest(),
        "output_redacted": True,
        "raw_output_persisted": False,
    }


def check_license_traceability() -> dict[str, Any]:
    register = LICENSE_REGISTER.read_text(encoding="utf-8") if LICENSE_REGISTER.exists() else ""
    notices = THIRD_PARTY_NOTICES.read_text(encoding="utf-8") if THIRD_PARTY_NOTICES.exists() else ""
    passed = bool(register and notices and "SPDX" in register and "精确版本/Commit" in notices)
    return {
        "name": "license-traceability-policy",
        "command": "read docs/07-research/UPSTREAM_REUSE_REGISTER.md and THIRD_PARTY_NOTICES.md",
        "status": "passed" if passed else "failed",
        "exit_code": 0 if passed else 1,
        "raw_output_persisted": False,
    }


def main() -> int:
    with tempfile.TemporaryDirectory(prefix="ragforge-phase6-security-") as temporary:
        inventory = Path(temporary) / "dependency-inventory.json"
        results = [
            run("phase6-security-corpus", [sys.executable, "-m", "unittest", "tests.security.test_phase6_security"]),
            run("phase2-egress-regression", [sys.executable, "-m", "unittest", "tests.security.test_phase2_egress_isolation"]),
            run("phase5-contract-security", [sys.executable, "-m", "unittest", "tests.contract.test_phase5_contracts"]),
            run(
                "agent-tool-security",
                [str(MAVEN), "--batch-mode", "--no-transfer-progress", "-pl", "apps/server", "-am", "-Dtest=AgentToolSecurityTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
                blocked_if_missing=True,
            ),
            run(
                "dependency-inventory-lockfiles",
                [sys.executable, "scripts/ci/dependency_inventory.py", "--require-lockfiles", "--output", str(inventory)],
            ),
        ]
        sbom = run(
            "sbom-vulnerability-image-scan",
            [sys.executable, "scripts/ci/sbom_dependency_scan.py", "--mode", "required"],
            blocked_if_missing=True,
        )
        if sbom["exit_code"] == 2:
            sbom["status"] = "blocked"
            sbom["reason"] = "syft or trivy is not installed; install in CI and rerun this exact command"
        results.append(sbom)
        results.append(check_license_traceability())

    passed_results = all(item["status"] == "passed" for item in results)
    report = {
        "evidence_version": "phase6-security.v1",
        "synthetic_only": True,
        "code_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "tests": results,
        "invariants": {
            "cross_space_leaks": 0 if results[0]["status"] == "passed" and results[2]["status"] == "passed" else None,
            "evidence_external_citations": 0 if results[0]["status"] == "passed" else None,
            "unauthorized_cloud_calls": 0 if results[1]["status"] == "passed" else None,
            "shell_sql_arbitrary_network_external_write": 0 if results[0]["status"] == "passed" else None,
            "ssrf_bypass": 0 if results[0]["status"] == "passed" else None,
            "path_traversal_zip_bomb_xxe_parser_ocr_bypass": 0 if results[0]["status"] == "passed" else None,
            "prompt_injection_tool_escalation": 0 if results[0]["status"] == "passed" else None,
            "raw_prompt_or_provider_body_persisted": 0,
            "dependency_image_scan_unaccepted_critical_high": 0 if sbom["status"] == "passed" else None,
        },
        "supply_chain": {
            "dependency_inventory": results[4]["status"],
            "sbom_vulnerability_image_scan": sbom["status"],
            "license_policy": results[6]["status"],
            "license_policy_evidence": [
                "docs/07-research/UPSTREAM_REUSE_REGISTER.md",
                "THIRD_PARTY_NOTICES.md",
            ],
        },
        "passed": passed_results,
    }
    serialized = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    sensitive_markers = ("FULL_PROMPT_FIXTURE", "fixture-secret-not-a-credential", '"provider_body"')
    if any(marker in serialized for marker in sensitive_markers):
        raise RuntimeError("security evidence contains a raw prompt, fixture secret, or provider body marker")
    OUTPUT.write_text(serialized, encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 0 if passed_results else 2


if __name__ == "__main__":
    raise SystemExit(main())
