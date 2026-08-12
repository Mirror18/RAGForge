#!/usr/bin/env python3
"""Run the format gate available before application toolchains are introduced."""

from __future__ import annotations

import json
import py_compile
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
TEXT_SUFFIXES = {".md", ".py", ".json", ".yaml", ".yml", ".xml", ".toml", ".txt"}


def tracked_files() -> list[Path]:
    result = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", "-z"],
        cwd=REPO_ROOT,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        print("Format check failed: git ls-files returned non-zero.", file=sys.stderr)
        raise SystemExit(result.returncode or 1)
    return [REPO_ROOT / item for item in result.stdout.decode().split("\0") if item]


def main() -> int:
    failures: list[str] = []
    files = [path for path in tracked_files() if path.is_file()]
    for path in files:
        if path.suffix.lower() in TEXT_SUFFIXES:
            try:
                lines = path.read_text(encoding="utf-8").splitlines()
            except (OSError, UnicodeDecodeError) as exc:
                failures.append(f"{path.relative_to(REPO_ROOT)}: unreadable text file: {exc}")
                continue
            for line_number, line in enumerate(lines, start=1):
                if line.rstrip() != line:
                    failures.append(f"{path.relative_to(REPO_ROOT)}:{line_number}: trailing whitespace")
    for path in (path for path in files if path.suffix == ".py"):
        try:
            py_compile.compile(str(path), doraise=True)
        except py_compile.PyCompileError as exc:
            failures.append(f"{path.relative_to(REPO_ROOT)}: Python compile failed: {exc.msg}")
    for path in (path for path in files if path.suffix == ".json"):
        try:
            json.loads(path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
            failures.append(f"{path.relative_to(REPO_ROOT)}: JSON parse failed: {exc}")
    if failures:
        print("Format gate failed:\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"Format gate passed: {len(files)} tracked files checked for whitespace, Python and JSON syntax.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
