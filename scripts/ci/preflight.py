#!/usr/bin/env python3
"""Run the local tool checks required by the RAGForge CI environment."""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Callable, Mapping, Sequence


TIMEOUT_SECONDS = 8
VERSION_PATTERN = re.compile(r"\d+(?:\.\d+){1,3}(?:[-+._][0-9A-Za-z.-]+)?")
MAVEN_JAVA_VERSION_PATTERN = re.compile(r"Java version:\s*(?:1\.)?(\d+)", re.IGNORECASE)
REQUIRED_JAVA_MAJOR = 21


@dataclass(frozen=True)
class CheckResult:
    code: str
    passed: bool
    required: bool
    result_code: str
    message: str
    version: str | None = None


Runner = Callable[..., subprocess.CompletedProcess[str]]
Which = Callable[[str], str | None]


def _extract_version(output: str) -> str | None:
    """Return a conservative version token; never return arbitrary tool text."""

    match = VERSION_PATTERN.search(output)
    return match.group(0) if match else None


def _run(command: Sequence[str]) -> subprocess.CompletedProcess[str]:
    """Run a fixed local command without a shell or network side effects."""

    return subprocess.run(
        list(command),
        capture_output=True,
        check=False,
        text=True,
        timeout=TIMEOUT_SECONDS,
    )


def _check_java_home(env: Mapping[str, str]) -> CheckResult:
    value = env.get("JAVA_HOME", "")
    if not value:
        return CheckResult(
            "java_home", False, True, "java_home_missing", "JAVA_HOME is not set; point it to a JDK directory."
        )
    try:
        is_directory = Path(value).is_dir()
    except (OSError, ValueError):
        is_directory = False
    if not is_directory:
        return CheckResult(
            "java_home",
            False,
            True,
            "java_home_not_directory",
            "JAVA_HOME must point to an existing JDK directory.",
        )
    return CheckResult("java_home", True, True, "ok", "JAVA_HOME points to a directory.")


def _check_command(
    check_code: str,
    executable: str,
    version_command: Sequence[str],
    which: Which,
    runner: Runner,
) -> CheckResult:
    executable_path = which(executable)
    if executable_path is None:
        return CheckResult(
            check_code,
            False,
            True,
            f"{check_code}_not_found",
            f"{executable} was not found on PATH; install it and add it to PATH.",
        )
    try:
        completed = runner((executable_path, *version_command[1:]))
    except (OSError, subprocess.TimeoutExpired):
        return CheckResult(
            check_code,
            False,
            True,
            f"{check_code}_failed",
            f"{executable} could not be executed; verify the local installation and PATH.",
        )
    version = _extract_version(f"{completed.stdout}\n{completed.stderr}")
    if completed.returncode != 0:
        return CheckResult(
            check_code,
            False,
            True,
            f"{check_code}_failed",
            f"{executable} returned a failure; verify the local installation and PATH.",
            version,
        )
    return CheckResult(check_code, True, True, "ok", f"{executable} is available on PATH.", version)


def _check_maven(which: Which, runner: Runner) -> CheckResult:
    """Check Maven and the JDK used by Maven's own launcher."""

    executable_path = which("mvn")
    if executable_path is None:
        return CheckResult(
            "maven", False, True, "maven_not_found",
            "mvn was not found on PATH; install Maven and add it to PATH.",
        )
    try:
        completed = runner((executable_path, "--version"))
    except (OSError, subprocess.TimeoutExpired):
        return CheckResult(
            "maven", False, True, "maven_failed",
            "mvn could not be executed; verify the local installation and PATH.",
        )
    output = f"{completed.stdout}\n{completed.stderr}"
    version = _extract_version(output)
    if completed.returncode != 0:
        return CheckResult(
            "maven", False, True, "maven_failed",
            "mvn returned a failure; verify the local installation and PATH.", version,
        )
    java_match = MAVEN_JAVA_VERSION_PATTERN.search(output)
    if java_match is None:
        return CheckResult(
            "maven", False, True, "maven_java_version_unknown",
            "Maven did not report the JDK it is using; configure Maven with a JDK 21 JAVA_HOME.", version,
        )
    java_major = int(java_match.group(1))
    if java_major < REQUIRED_JAVA_MAJOR:
        return CheckResult(
            "maven", False, True, "maven_jdk_too_old",
            f"Maven is bound to Java {java_major}; set JAVA_HOME to JDK {REQUIRED_JAVA_MAJOR} or newer before running Maven.",
            version,
        )
    return CheckResult(
        "maven", True, True, "ok",
        f"mvn is available on PATH and is bound to Java {java_major}.", version,
    )


def _check_docker(which: Which, runner: Runner) -> CheckResult:
    executable_path = which("docker")
    if executable_path is None:
        return CheckResult(
            "docker_daemon",
            False,
            True,
            "docker_not_found",
            "docker was not found on PATH; install Docker and add it to PATH.",
        )
    try:
        version_result = runner((executable_path, "--version"))
    except (OSError, subprocess.TimeoutExpired):
        return CheckResult("docker_daemon", False, True, "docker_failed", "Docker CLI could not be executed locally.")
    version = _extract_version(f"{version_result.stdout}\n{version_result.stderr}")
    if version_result.returncode != 0:
        return CheckResult("docker_daemon", False, True, "docker_failed", "Docker CLI returned a failure.", version)
    try:
        daemon_result = runner((executable_path, "info"))
    except (OSError, subprocess.TimeoutExpired):
        return CheckResult(
            "docker_daemon",
            False,
            True,
            "docker_daemon_unavailable",
            "Docker daemon is unavailable; start Docker Desktop or the local daemon.",
            version,
        )
    if daemon_result.returncode != 0:
        return CheckResult(
            "docker_daemon",
            False,
            True,
            "docker_daemon_unavailable",
            "Docker daemon is unavailable; start Docker Desktop or the local daemon.",
            version,
        )
    return CheckResult("docker_daemon", True, True, "ok", "Docker daemon is reachable.", version)


def run_preflight(
    env: Mapping[str, str] | None = None,
    which: Which = shutil.which,
    runner: Runner = _run,
) -> dict[str, object]:
    """Return the stable preflight report without printing or mutating state."""

    environment = os.environ if env is None else env
    checks: list[CheckResult] = [
        _check_java_home(environment),
        _check_command("java", "java", ("java", "-version"), which, runner),
        _check_maven(which, runner),
        _check_command("node", "node", ("node", "--version"), which, runner),
        _check_command("npm", "npm", ("npm", "--version"), which, runner),
        _check_docker(which, runner),
    ]
    remediation = [
        {"code": check.result_code, "message": check.message}
        for check in checks
        if not check.passed
    ]
    tool_versions = {
        "java": next(check.version for check in checks if check.code == "java"),
        "maven": next(check.version for check in checks if check.code == "maven"),
        "node": next(check.version for check in checks if check.code == "node"),
        "npm": next(check.version for check in checks if check.code == "npm"),
        "docker": next(check.version for check in checks if check.code == "docker_daemon"),
    }
    return {
        "passed": all(check.passed for check in checks if check.required),
        "checks": [asdict(check) for check in checks],
        "tool_versions": tool_versions,
        "remediation": remediation,
    }


def _print_human(report: Mapping[str, object]) -> None:
    print("Preflight: " + ("PASS" if report["passed"] else "FAIL"))
    for check in report["checks"]:  # type: ignore[union-attr]
        state = "PASS" if check["passed"] else "FAIL"
        print(f"[{state}] {check['code']}: {check['message']} ({check['result_code']})")
    for item in report["remediation"]:  # type: ignore[union-attr]
        print(f"Remediation [{item['code']}]: {item['message']}")


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Check local RAGForge CI prerequisites.")
    parser.add_argument("--json", action="store_true", help="emit only the stable JSON report")
    parser.add_argument("--strict", action="store_true", help="return non-zero when a required check fails")
    args = parser.parse_args(argv)
    report = run_preflight()
    if args.json:
        print(json.dumps(report, ensure_ascii=False, sort_keys=True, separators=(",", ":")))
    else:
        _print_human(report)
    return 1 if args.strict and not report["passed"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
