#!/usr/bin/env python3
"""RF-7: reorganize backend/server into ADR-0014 business + four layers.

Target layout:
  com.ragforge.server.{space|knowledge|answer|shared}.{api|application|domain|infrastructure}

Uses class-level FQCN remapping because one old package splits across layers.
"""

from __future__ import annotations

import re
import shutil
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN_SRC = ROOT / "backend" / "server" / "src" / "main" / "java" / "com" / "ragforge" / "server"
TEST_SRC = ROOT / "backend" / "server" / "src" / "test" / "java" / "com" / "ragforge" / "server"
OLD_PREFIX = "com.ragforge.server"

BUSINESS_BY_TOP = {
    "identity": "space",
    "space": "space",
    "provider": "space",
    "ingestion": "knowledge",
    "chunk": "knowledge",
    "index": "knowledge",
    "retrieval": "knowledge",
    "embedding": "knowledge",
    "studio": "knowledge",
    "answer": "answer",
    "run": "answer",
    "prompt": "answer",
    "agent": "answer",
    "ops": "shared",
    "audit": "shared",
    "common": "shared",
    "config": "shared",
}

LAYER_ALIASES = {
    "api": "api",
    "integration": "infrastructure",
    "persistence": "infrastructure",
    "adapter": "infrastructure",
}


@dataclass(frozen=True)
class Move:
    src: Path
    dest: Path
    old_pkg: str
    new_pkg: str
    class_name: str

    @property
    def old_fqcn(self) -> str:
        return f"{self.old_pkg}.{self.class_name}"

    @property
    def new_fqcn(self) -> str:
        return f"{self.new_pkg}.{self.class_name}"


def classify_layer(rel_parts: tuple[str, ...], filename: str) -> str:
    if len(rel_parts) >= 2 and rel_parts[1] in LAYER_ALIASES:
        return LAYER_ALIASES[rel_parts[1]]
    name = filename
    if name.endswith(("Controller.java", "Filter.java", "Cli.java")):
        return "api"
    if (
        name.endswith(("Repository.java", "Configuration.java", "Config.java", "Runner.java", "Store.java", "Client.java"))
        or name.startswith("Jdbc")
        or "Adapter" in name
    ):
        return "infrastructure"
    if name.endswith((
        "Service.java", "Port.java", "Resolver.java", "Publisher.java", "Observer.java",
        "Factory.java", "Decoder.java", "Engine.java", "Handler.java", "Builder.java",
        "Support.java", "Issuer.java", "Parser.java", "Provider.java", "Authorizer.java",
        "Recorder.java", "Merger.java", "Reranker.java", "Validator.java", "Transitions.java",
        "Projection.java", "Snapshot.java",
    )):
        return "application"
    return "domain"


def map_relative(rel: Path) -> Path | None:
    parts = rel.parts
    if not parts or parts[0] not in BUSINESS_BY_TOP:
        return None
    business = BUSINESS_BY_TOP[parts[0]]
    filename = parts[-1]
    layer = classify_layer(parts, filename)
    mid = [part for part in parts[1:-1] if part not in LAYER_ALIASES]
    return Path(business) / layer / Path(*mid) / filename


def package_of(rel: Path) -> str:
    if len(rel.parts) == 1:
        return OLD_PREFIX
    return OLD_PREFIX + "." + ".".join(rel.parts[:-1])


def collect_moves(base: Path) -> list[Move]:
    moves: list[Move] = []
    if not base.is_dir():
        return moves
    for path in sorted(base.rglob("*.java")):
        rel = path.relative_to(base)
        mapped = map_relative(rel)
        if mapped is None:
            continue
        dest = base / mapped
        old_pkg = package_of(rel)
        new_pkg = package_of(mapped)
        class_name = path.stem
        if dest.resolve() == path.resolve() and old_pkg == new_pkg:
            continue
        moves.append(Move(path, dest, old_pkg, new_pkg, class_name))
    return moves


def rewrite_text(text: str, fqcn_map: dict[str, str], pkg_map: dict[str, str]) -> str:
    for old, new in sorted(fqcn_map.items(), key=lambda item: len(item[0]), reverse=True):
        text = text.replace(old, new)
    # Star-imports and package declarations for packages that moved wholesale when unique.
    for old, new in sorted(pkg_map.items(), key=lambda item: len(item[0]), reverse=True):
        text = re.sub(rf"(?m)^package\s+{re.escape(old)}\s*;", f"package {new};", text)
        text = re.sub(rf"(?m)^import\s+{re.escape(old)}\.\*;", f"import {new}.*;", text)
        text = re.sub(rf"(?m)^import\s+static\s+{re.escape(old)}\.", f"import static {new}.", text)
    return text


def apply(base: Path, moves: list[Move], fqcn_map: dict[str, str], pkg_map: dict[str, str]) -> None:
    originals: dict[Path, str] = {
        path: path.read_text(encoding="utf-8") for path in base.rglob("*.java")
    }
    moved_srcs = {move.src for move in moves}

    for move in moves:
        body = originals[move.src]
        body = re.sub(
            rf"(?m)^package\s+{re.escape(move.old_pkg)}\s*;",
            f"package {move.new_pkg};",
            body,
            count=1,
        )
        body = rewrite_text(body, fqcn_map, pkg_map)
        move.dest.parent.mkdir(parents=True, exist_ok=True)
        move.dest.write_text(body, encoding="utf-8")
        if move.src.resolve() != move.dest.resolve():
            move.src.unlink()

    for path, body in originals.items():
        if path in moved_srcs:
            continue
        if not path.exists():
            continue
        updated = rewrite_text(body, fqcn_map, pkg_map)
        if updated != body:
            path.write_text(updated, encoding="utf-8")

    for top in BUSINESS_BY_TOP:
        top_dir = base / top
        if top_dir.is_dir() and not any(top_dir.rglob("*.java")):
            shutil.rmtree(top_dir)


def unique_pkg_map(moves: list[Move]) -> dict[str, str]:
    """Only rewrite package X -> Y when every class from X lands in the same Y."""
    buckets: dict[str, set[str]] = {}
    for move in moves:
        buckets.setdefault(move.old_pkg, set()).add(move.new_pkg)
    return {old: next(iter(news)) for old, news in buckets.items() if len(news) == 1}


def main() -> int:
    main_moves = collect_moves(MAIN_SRC)
    test_moves = collect_moves(TEST_SRC)
    all_moves = main_moves + test_moves
    fqcn_map = {move.old_fqcn: move.new_fqcn for move in all_moves}
    pkg_map = unique_pkg_map(all_moves)

    print(f"main moves: {len(main_moves)}")
    print(f"test moves: {len(test_moves)}")
    print(f"fqcn renames: {len(fqcn_map)}")
    print(f"unique package renames: {len(pkg_map)}")

    apply(MAIN_SRC, main_moves, fqcn_map, pkg_map)
    apply(TEST_SRC, test_moves, fqcn_map, pkg_map)

    # Rewrite docs/config that may mention old FQCNs
    for path in (ROOT / "backend" / "server").rglob("*"):
        if not path.is_file() or path.suffix.lower() not in {".md", ".yml", ".yaml", ".xml", ".properties", ".java"}:
            continue
        text = path.read_text(encoding="utf-8")
        updated = rewrite_text(text, fqcn_map, pkg_map)
        if updated != text:
            path.write_text(updated, encoding="utf-8")
            print(f"rewrote: {path.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
