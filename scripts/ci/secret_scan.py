#!/usr/bin/env python3
"""Scan tracked text files for common credential formats."""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
PATTERNS = (
    re.compile(r"-----BEGIN [A-Z ]+PRIVATE KEY-----"),
    re.compile(r"\bAKIA[0-9A-Z]{16}\b"),
    re.compile(r"\b(?:gh[pousr]|github_pat)_[A-Za-z0-9_]{20,}\b"),
    re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
    re.compile(r"\b(?:sk|rk)-[A-Za-z0-9]{20,}\b"),
    re.compile(
        r"(?i)(?:api[_-]?key|secret[_-]?key|access[_-]?key|password)\s*[:=]\s*[\"']?"
        r"([A-Za-z0-9+/=_-]{24,})"
    ),
)
PLACEHOLDER_VALUES = {"change-me", "change-me-minio-secret", "placeholder", "example", "test", "none", "null"}


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=REPO_ROOT,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        print("Secret scan failed: git ls-files returned non-zero.", file=sys.stderr)
        raise SystemExit(result.returncode or 1)
    return [REPO_ROOT / item for item in result.stdout.decode().split("\0") if item]


def scan(path: Path) -> list[str]:
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return []
    findings: list[str] = []
    for line_number, line in enumerate(text.splitlines(), start=1):
        for pattern in PATTERNS:
            match = pattern.search(line)
            if not match:
                continue
            if pattern.pattern.startswith("(?i)(?:") and match.group(1).lower() in PLACEHOLDER_VALUES:
                continue
            findings.append(f"{path.relative_to(REPO_ROOT)}:{line_number}: matched credential pattern")
            break
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.parse_args()
    findings = [finding for path in tracked_files() if path.is_file() for finding in scan(path)]
    if findings:
        print("Secret scan failed:", file=sys.stderr)
        print("\n".join(findings), file=sys.stderr)
        return 1
    print("Secret scan passed: no supported credential pattern found in tracked text files.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
