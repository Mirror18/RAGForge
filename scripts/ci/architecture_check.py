#!/usr/bin/env python3
"""Check the modular-monolith deployment boundary and Compose profiles."""

from __future__ import annotations

import re
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[2]
COMPOSE_FILE = REPO_ROOT / "deploy" / "compose" / "compose.yaml"
UNIFIED_DOCKERFILE = REPO_ROOT / "deploy" / "docker" / "Dockerfile"
WEB_PROXY_CONFIG = REPO_ROOT / "deploy" / "docker" / "nginx.conf"
BUSINESS_SERVICE_NAMES = {"server", "worker", "web", "ai-runtime", "ragforge-server", "ragforge-worker"}


def main() -> int:
    if not COMPOSE_FILE.is_file():
        print(f"Architecture check failed: missing {COMPOSE_FILE}", file=sys.stderr)
        return 2
    missing_build_files = [path for path in (UNIFIED_DOCKERFILE, WEB_PROXY_CONFIG) if not path.is_file()]
    if missing_build_files:
        print(f"Architecture check failed: missing unified Docker files: {missing_build_files}", file=sys.stderr)
        return 2
    source = COMPOSE_FILE.read_text(encoding="utf-8")
    services_section = source.split("\nservices:\n", 1)[-1].split("\nnetworks:\n", 1)[0]
    service_blocks: dict[str, list[str]] = {}
    current_service: str | None = None
    for line in services_section.splitlines():
        match = re.fullmatch(r"  ([a-z][a-z0-9-]*):", line)
        if match:
            current_service = match.group(1)
            service_blocks[current_service] = []
        elif current_service:
            service_blocks[current_service].append(line)
    unprofiled_business_services = sorted(
        name
        for name in BUSINESS_SERVICE_NAMES & set(service_blocks)
        if not any("- app" in line for line in service_blocks[name])
    )
    if unprofiled_business_services:
        print(
            "Architecture check failed: application services must be isolated behind the app profile: "
            + ", ".join(unprofiled_business_services),
            file=sys.stderr,
        )
        return 1
    required_dirs = ("apps/server", "apps/ingestion-worker", "apps/web", "apps/ai-runtime", "contracts")
    missing_dirs = [path for path in required_dirs if not (REPO_ROOT / path).is_dir()]
    if missing_dirs:
        print(f"Architecture check failed: missing ownership boundary directories: {missing_dirs}", file=sys.stderr)
        return 1
    print(
        "Architecture check passed: modular-monolith/worker directories exist; core services are "
        "unprofiled and application services are isolated behind app."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
