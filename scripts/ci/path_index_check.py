#!/usr/bin/env python3
"""Check that canonical deployment and local-start paths stay indexed."""

from __future__ import annotations

import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
REQUIRED_FILES = (
    REPO_ROOT / "deploy" / "compose" / "compose.yaml",
    REPO_ROOT / "deploy" / "docker" / "Dockerfile",
    REPO_ROOT / "deploy" / "docker" / "nginx.conf",
    REPO_ROOT / "scripts" / "dev" / "core.py",
    REPO_ROOT / "scripts" / "dev" / "start-local.bat",
    REPO_ROOT / "scripts" / "dev" / "start-local.ps1",
)
INDEX_REQUIREMENTS = {
    REPO_ROOT / "README.md": (
        "deploy/README.md",
        "deploy/docker/Dockerfile",
        "deploy/compose/compose.yaml",
        "scripts/dev/start-local.bat",
    ),
    REPO_ROOT / "deploy" / "README.md": (
        "docker/Dockerfile",
        "compose/",
        "scripts/dev/start-local.bat",
    ),
    REPO_ROOT / "scripts" / "README.md": (
        "dev/core.py",
        "start-local.bat",
        "../deploy/docker/Dockerfile",
    ),
    REPO_ROOT / "docs" / "05-operations" / "DEPLOYMENT.md": (
        "deploy/compose/compose.yaml",
        "deploy/docker/Dockerfile",
        "scripts/dev/core.py",
        "scripts/dev/start-local.bat",
    ),
}
FORBIDDEN_REFERENCES = ("apps/server/Dockerfile",)


def main() -> int:
    missing_files = [str(path.relative_to(REPO_ROOT)) for path in REQUIRED_FILES if not path.is_file()]
    if missing_files:
        print(f"Path index check failed: missing canonical files: {missing_files}", file=sys.stderr)
        return 1

    missing_references: list[str] = []
    forbidden_references: list[str] = []
    for index_path, references in INDEX_REQUIREMENTS.items():
        if not index_path.is_file():
            missing_references.append(f"{index_path.relative_to(REPO_ROOT)} (index missing)")
            continue
        content = index_path.read_text(encoding="utf-8")
        missing_references.extend(
            f"{index_path.relative_to(REPO_ROOT)} -> {reference}"
            for reference in references
            if reference not in content
        )
        forbidden_references.extend(
            f"{index_path.relative_to(REPO_ROOT)} -> {reference}"
            for reference in FORBIDDEN_REFERENCES
            if reference in content
        )

    if missing_references or forbidden_references:
        if missing_references:
            print(f"Path index check failed: missing references: {missing_references}", file=sys.stderr)
        if forbidden_references:
            print(f"Path index check failed: stale references: {forbidden_references}", file=sys.stderr)
        return 1

    print("Path index check passed: canonical Docker, Compose, and local-start paths are indexed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
