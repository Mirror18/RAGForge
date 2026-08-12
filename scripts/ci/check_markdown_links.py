#!/usr/bin/env python3
"""Check repository-relative Markdown links without third-party dependencies."""

from __future__ import annotations

import argparse
import os
import re
import sys
from pathlib import Path
from urllib.parse import unquote


REPO_ROOT = Path(__file__).resolve().parents[2]
LINK_PATTERN = re.compile(r"(?<!!)\[[^\]]+\]\((?:<([^>]+)>|([^\s)]+))")
SKIP_SCHEMES = ("http://", "https://", "mailto:", "tel:", "data:")
SKIP_DIR_NAMES = frozenset(
    {
        ".git",
        ".vite",
        "__pycache__",
        "coverage",
        "dist",
        "generated",
        "node_modules",
        "reports",
        "target",
        "tmp",
    }
)


def iter_markdown(root: Path):
    """Yield repository Markdown while pruning ignored/generated directories."""
    for current, directories, filenames in os.walk(root):
        directories[:] = sorted(directory for directory in directories if directory not in SKIP_DIR_NAMES)
        for filename in sorted(filenames):
            if filename.endswith(".md"):
                yield Path(current) / filename


def check_file(path: Path) -> list[str]:
    errors: list[str] = []
    in_fence = False
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if line.strip().startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        for match in LINK_PATTERN.finditer(line):
            target = match.group(1) or match.group(2) or ""
            target = unquote(target)
            target_path = target.split("#", 1)[0].split("?", 1)[0]
            if not target_path or target.startswith(SKIP_SCHEMES) or target.startswith("#"):
                continue
            candidate = (path.parent / target_path).resolve()
            try:
                candidate.relative_to(REPO_ROOT)
            except ValueError:
                errors.append(f"{path.relative_to(REPO_ROOT)}:{line_number}: link escapes repository: {target}")
                continue
            if not candidate.exists():
                errors.append(f"{path.relative_to(REPO_ROOT)}:{line_number}: missing target: {target}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=REPO_ROOT)
    args = parser.parse_args()
    root = args.root.resolve()
    errors = [error for path in iter_markdown(root) for error in check_file(path)]
    if errors:
        print("Markdown link check failed:", file=sys.stderr)
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Markdown link check passed: {len(list(iter_markdown(root)))} files scanned.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
