#!/usr/bin/env python3
"""Move server tests into the same package as the production type they white-box."""

from __future__ import annotations

import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "backend" / "server" / "src" / "main" / "java"
TEST = ROOT / "backend" / "server" / "src" / "test" / "java"
PACKAGE_RE = re.compile(r"(?m)^package\s+([\w.]+)\s*;")

MANUAL = {
    "BusinessIngestionPathPolicyTest": "BusinessIngestionService",
    "StudioControllerMockMvcTest": "ChunkStudioController",
    "MultiInstanceRunEventFanoutIntegrationTest": "JdbcRunEventStore",
    "Phase5PersistenceIntegrationTest": "JdbcRunEventStore",
    "Phase3IngestionPersistenceIntegrationTest": "BusinessIngestionService",
    "Phase4ChunkIndexPersistenceIntegrationTest": "ChunkRepository",
    "Phase2PersistenceIntegrationTest": "SpaceRepository",
    "Phase5ProviderIntegrationTest": "ProviderBackedGenerationPort",
    "Phase5RealOllamaRagE2ETest": "RAGAnswerService",
    "LocalOllamaRunAcceptanceTest": "RunExecutionService",
}


def production_packages() -> dict[str, str]:
    mapping: dict[str, str] = {}
    for path in MAIN.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        match = PACKAGE_RE.search(text)
        if match:
            mapping[path.stem] = match.group(1)
    return mapping


def tested_name(stem: str, prod: dict[str, str]) -> str | None:
    if stem in MANUAL:
        return MANUAL[stem]
    # Prefer exact production type + "Test" / "IntegrationTest"
    for suffix in ("IntegrationTest", "HttpTest", "MockMvcTest", "Test"):
        if stem.endswith(suffix):
            candidate = stem[: -len(suffix)]
            if candidate in prod:
                return candidate
    return None


def main() -> int:
    prod = production_packages()
    moved = 0
    for path in sorted(TEST.rglob("*Test.java")):
        target = tested_name(path.stem, prod)
        if target is None or target not in prod:
            continue
        new_pkg = prod[target]
        text = path.read_text(encoding="utf-8")
        old_match = PACKAGE_RE.search(text)
        if not old_match:
            continue
        if old_match.group(1) == new_pkg:
            continue
        dest = TEST / Path(*new_pkg.split(".")) / path.name
        dest.parent.mkdir(parents=True, exist_ok=True)
        updated = PACKAGE_RE.sub(f"package {new_pkg};", text, count=1)
        dest.write_text(updated, encoding="utf-8")
        if dest.resolve() != path.resolve():
            path.unlink()
        moved += 1
        print(f"{old_match.group(1)}.{path.stem} -> {new_pkg}")

    for directory in sorted(TEST.rglob("*"), reverse=True):
        if directory.is_dir() and not any(directory.rglob("*.java")):
            shutil.rmtree(directory, ignore_errors=True)
    print(f"moved: {moved}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
