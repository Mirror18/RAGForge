#!/usr/bin/env python3
"""Validate dependency manifest/lockfile pairing and emit an auditable inventory."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--require-lockfiles", action="store_true")
    args = parser.parse_args()
    java_manifests = sorted(REPO_ROOT.rglob("pom.xml"))
    node_manifests = sorted(path for path in REPO_ROOT.rglob("package.json") if "node_modules" not in path.parts)
    node_lockfiles = sorted(path for path in REPO_ROOT.rglob("package-lock.json") if "node_modules" not in path.parts)
    lock_dirs = {path.parent.resolve() for path in node_lockfiles}
    missing_node_locks = [path for path in node_manifests if path.parent.resolve() not in lock_dirs]
    report = {
        "status": "passed" if not (args.require_lockfiles and missing_node_locks) else "failed",
        "java_manifests": [str(path.relative_to(REPO_ROOT)) for path in java_manifests],
        "node_manifests": [str(path.relative_to(REPO_ROOT)) for path in node_manifests],
        "node_lockfiles": [str(path.relative_to(REPO_ROOT)) for path in node_lockfiles],
        "node_manifests_without_lockfile": [str(path.relative_to(REPO_ROOT)) for path in missing_node_locks],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.output:
        output = args.output.resolve()
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if missing_node_locks and args.require_lockfiles:
        print("Dependency inventory failed: every package.json must have a package-lock.json.", file=sys.stderr)
        return 1
    print("Dependency inventory passed: manifest/lockfile inventory is auditable.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
