#!/usr/bin/env python3
"""Executable SBOM/dependency-scan seam with an explicit Phase 1 placeholder mode."""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("auto", "placeholder", "required"), default="auto")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    syft = shutil.which("syft")
    trivy = shutil.which("trivy")
    if args.mode == "placeholder" or (args.mode == "auto" and not syft and not trivy):
        report = {
            "status": "placeholder",
            "scope": str(REPO_ROOT),
            "required_follow_up": "Install syft or trivy and switch CI to --mode required before release.",
        }
        print(json.dumps(report, ensure_ascii=False, indent=2))
        print("::notice title=SBOM/dependency scan::Phase 1 placeholder executed; release gate must enable syft/trivy.")
        if args.output:
            args.output.resolve().write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return 0
    if args.mode == "required" and not syft and not trivy:
        print("SBOM/dependency scan failed: --mode required needs syft or trivy on PATH.", file=sys.stderr)
        return 2
    tool = syft or trivy
    command = [tool, "dir:" + str(REPO_ROOT), "-o", "json"] if syft else [tool, "fs", "--scanners", "vuln", str(REPO_ROOT)]
    print("$ " + " ".join(command))
    result = subprocess.run(command, cwd=REPO_ROOT, check=False)
    return result.returncode


if __name__ == "__main__":
    raise SystemExit(main())
