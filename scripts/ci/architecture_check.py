#!/usr/bin/env python3
"""Check the Phase 1 deployment boundary before business services are added."""

from __future__ import annotations

import re
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO_ROOT / "deploy" / "compose" / "compose.yaml"
BUSINESS_SERVICE_NAMES = {"server", "worker", "web", "ai-runtime", "ragforge-server", "ragforge-worker"}


def main() -> int:
    if not COMPOSE_FILE.is_file():
        print(f"Architecture check failed: missing {COMPOSE_FILE}", file=sys.stderr)
        return 2
    source = COMPOSE_FILE.read_text(encoding="utf-8")
    service_names = set(re.findall(r"^  ([a-z][a-z0-9-]*):\s*$", source, flags=re.MULTILINE))
    business_services = sorted(service_names & BUSINESS_SERVICE_NAMES)
    if business_services:
        print(
            "Architecture check failed: core Compose must not invent application services: "
            + ", ".join(business_services),
            file=sys.stderr,
        )
        return 1
    required_dirs = ("apps/server", "apps/ingestion-worker", "apps/web", "apps/ai-runtime", "contracts")
    missing_dirs = [path for path in required_dirs if not (REPO_ROOT / path).is_dir()]
    if missing_dirs:
        print(f"Architecture check failed: missing ownership boundary directories: {missing_dirs}", file=sys.stderr)
        return 1
    print(
        "Architecture check passed: modular-monolith/worker directories exist and core Compose "
        "contains infrastructure only."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
