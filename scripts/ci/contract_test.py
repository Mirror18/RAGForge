#!/usr/bin/env python3
"""Run the available contract checks without assuming application code exists."""

from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
CONTRACT_ROOT = REPO_ROOT / "contracts"


def main() -> int:
    if not CONTRACT_ROOT.is_dir():
        print(f"Contract test failed: directory missing: {CONTRACT_ROOT}", file=sys.stderr)
        return 2
    test_root = REPO_ROOT / "tests" / "contract"
    if not test_root.is_dir():
        print(f"Contract test failed: test directory missing: {test_root}", file=sys.stderr)
        return 2
    test_files = sorted(test_root.glob("test_*.py"))
    command = [sys.executable, "-m", "unittest", "discover", "-s", "tests/contract", "-p", "test_*.py", "-v"]
    print("$ " + " ".join(command))
    result = subprocess.run(command, cwd=REPO_ROOT, check=False)
    if result.returncode != 0:
        if result.returncode == 5 and not test_files:
            print("Contract unittest returned 5 because the test directory is empty; treating the explicit empty-skeleton state as a placeholder.")
        else:
            print(f"Contract test failed: unittest exit code {result.returncode}.", file=sys.stderr)
            return result.returncode
    if test_files:
        print(f"Contract unittest passed: {len(test_files)} test module(s) executed.")
    else:
        print("Contract unittest passed with no test_*.py modules; add contract tests before application implementation.")
    files = sorted(path for path in CONTRACT_ROOT.rglob("*") if path.is_file() and path.suffix.lower() in {".json", ".yaml", ".yml"})
    if not files:
        print("Contract artifact placeholder: no JSON/YAML contract artifact exists yet.")
        return 0
    failures: list[str] = []
    for path in files:
        if path.suffix.lower() == ".json":
            try:
                json.loads(path.read_text(encoding="utf-8"))
            except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
                failures.append(f"{path.relative_to(REPO_ROOT)}: invalid JSON: {exc}")
        elif not path.read_text(encoding="utf-8").strip():
            failures.append(f"{path.relative_to(REPO_ROOT)}: empty YAML contract")
    if failures:
        print("Contract test failed:\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"Contract test passed: {len(files)} contract artifact(s) checked.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
