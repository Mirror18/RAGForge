#!/usr/bin/env python3
"""Add missing imports after RF-7 same-package splits."""

from __future__ import annotations

import re
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA_ROOTS = [
    ROOT / "backend" / "server" / "src" / "main" / "java",
    ROOT / "backend" / "server" / "src" / "test" / "java",
]

PACKAGE_RE = re.compile(r"(?m)^package\s+([\w.]+)\s*;")
IMPORT_RE = re.compile(r"(?m)^import\s+(?:static\s+)?([\w.]+)\s*;")
TYPE_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\b")


def collect_types() -> dict[str, set[str]]:
    by_simple: dict[str, set[str]] = defaultdict(set)
    for root in JAVA_ROOTS:
        for path in root.rglob("*.java"):
            text = path.read_text(encoding="utf-8")
            match = PACKAGE_RE.search(text)
            if not match:
                continue
            pkg = match.group(1)
            simple = path.stem
            # Skip nested/top-level ambiguity for inner types; file stem is enough for our code.
            by_simple[simple].add(f"{pkg}.{simple}")
    return by_simple


def ensure_imports(path: Path, by_simple: dict[str, set[str]]) -> bool:
    text = path.read_text(encoding="utf-8")
    pkg_match = PACKAGE_RE.search(text)
    if not pkg_match:
        return False
    pkg = pkg_match.group(1)
    existing = set(IMPORT_RE.findall(text))
    existing_simple = {item.rsplit(".", 1)[-1] for item in existing}
    needed: list[str] = []
    self_name = path.stem
    for simple in sorted(set(TYPE_RE.findall(text))):
        if simple == self_name or simple in existing_simple:
            continue
        candidates = by_simple.get(simple, set())
        if len(candidates) != 1:
            continue
        fqcn = next(iter(candidates))
        other_pkg = fqcn.rsplit(".", 1)[0]
        if other_pkg == pkg or fqcn in existing:
            continue
        # Only auto-import types that live under com.ragforge.server
        if not fqcn.startswith("com.ragforge.server."):
            continue
        needed.append(fqcn)
    if not needed:
        return False

    insert_at = 0
    lines = text.splitlines(keepends=True)
    last_import = -1
    package_line = -1
    for index, line in enumerate(lines):
        if line.startswith("package "):
            package_line = index
        if line.startswith("import "):
            last_import = index
    if last_import >= 0:
        insert_at = last_import + 1
    elif package_line >= 0:
        insert_at = package_line + 1
        if insert_at < len(lines) and lines[insert_at].strip():
            lines.insert(insert_at, "\n")
            insert_at += 1
    else:
        return False

    import_lines = [f"import {fqcn};\n" for fqcn in needed]
    # Keep a blank line after imports if next line is not blank and not import.
    lines[insert_at:insert_at] = import_lines
    path.write_text("".join(lines), encoding="utf-8")
    return True


def main() -> int:
    by_simple = collect_types()
    changed = 0
    for root in JAVA_ROOTS:
        for path in sorted(root.rglob("*.java")):
            if ensure_imports(path, by_simple):
                changed += 1
                print(f"imports fixed: {path.relative_to(ROOT)}")
    print(f"files updated: {changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
