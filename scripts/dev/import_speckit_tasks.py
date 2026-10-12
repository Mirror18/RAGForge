#!/usr/bin/env python3
"""Import speckit tasks.md checkboxes into backlog/tasks (idempotent)."""

from __future__ import annotations

import argparse
import datetime
import pathlib
import re
import sys

TASK_LINE = re.compile(r"^- \[([ xX])\] (T\d+)\s+(.*)$")
ID_LINE = re.compile(r"^id:\s*(RF-\d+)\s*$", re.IGNORECASE)
SOURCE_SPEC_LINE = re.compile(r"^source_spec:\s*(.+)\s*$")
SOURCE_TASK_LINE = re.compile(r"^source_task:\s*(T\d+)\s*$")
STATUS_LINE = re.compile(r"^status:\s*(.+)\s*$")
TITLE_LINE = re.compile(r"^title:\s*(.+)\s*$")

def repo_root() -> pathlib.Path:
    return pathlib.Path(__file__).resolve().parents[2]


def now_stamp() -> str:
    return datetime.datetime.now().strftime("%Y-%m-%d %H:%M")


def parse_tasks_md(path: pathlib.Path) -> list[tuple[str, str, bool]]:
    rows: list[tuple[str, str, bool]] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        match = TASK_LINE.match(raw.strip())
        if not match:
            continue
        done = match.group(1).lower() == "x"
        rows.append((match.group(2), match.group(3).strip(), done))
    return rows


def parse_frontmatter(text: str) -> dict[str, str]:
    if not text.startswith("---"):
        return {}
    end = text.find("\n---", 3)
    if end < 0:
        return {}
    fields: dict[str, str] = {}
    for line in text[4:end].splitlines():
        stripped = line.strip()
        for pattern, key in (
            (ID_LINE, "id"),
            (SOURCE_SPEC_LINE, "source_spec"),
            (SOURCE_TASK_LINE, "source_task"),
            (STATUS_LINE, "status"),
            (TITLE_LINE, "title"),
        ):
            match = pattern.match(stripped)
            if match:
                fields[key] = match.group(1).strip().strip('"')
    return fields


def next_rf_id(existing_ids: list[str]) -> str:
    numbers = []
    for item in existing_ids:
        match = re.match(r"RF-(\d+)$", item, re.IGNORECASE)
        if match:
            numbers.append(int(match.group(1)))
    return f"RF-{max(numbers, default=0) + 1}"


def slugify(rf_id: str, spec_id: str, task_id: str) -> str:
    return f"{rf_id.lower()} - speckit-{spec_id}-{task_id}.md"


def card_text(
    *,
    rf_id: str,
    title: str,
    status: str,
    spec_id: str,
    task_id: str,
    description: str,
    created: str,
    updated: str,
    milestone: str,
    ordinal: int,
    token_limit: int,
) -> str:
    checked = "x" if status == "Done" else " "
    return f"""---
id: {rf_id}
title: {title}
status: {status}
assignee: []
created_date: '{created}'
updated_date: '{updated}'
labels:
  - speckit
source: speckit
source_spec: {spec_id}
source_task: {task_id}
milestone: {milestone}
dependencies: []
priority: medium
ordinal: {ordinal}
token_limit: {token_limit}
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
来自 `specs/{spec_id}/tasks.md` 的 {task_id}。

{description}
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [{checked}] #1 {task_id} 按规格完成，并在 `specs/{spec_id}/tasks.md` 勾选一致
<!-- AC:END -->
"""


def load_board(tasks_dir: pathlib.Path) -> tuple[list[pathlib.Path], dict[tuple[str, str], pathlib.Path], list[str]]:
    files = sorted(tasks_dir.glob("*.md"))
    by_source: dict[tuple[str, str], pathlib.Path] = {}
    ids: list[str] = []
    for path in files:
        fields = parse_frontmatter(path.read_text(encoding="utf-8"))
        if "id" in fields:
            ids.append(fields["id"].upper())
        if "source_spec" in fields and "source_task" in fields:
            by_source[(fields["source_spec"], fields["source_task"])] = path
    return files, by_source, ids


def replace_status_and_title(text: str, title: str, status: str, updated: str) -> str:
    text = re.sub(r"^title:.*$", f"title: {title}", text, count=1, flags=re.MULTILINE)
    text = re.sub(r"^status:.*$", f"status: {status}", text, count=1, flags=re.MULTILINE)
    text = re.sub(r"^updated_date:.*$", f"updated_date: '{updated}'", text, count=1, flags=re.MULTILINE)
    text = re.sub(
        r"^- \[[ xX]\] #1 ",
        f"- [{'x' if status == 'Done' else ' '}] #1 ",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    return text


def milestone_for(spec_id: str) -> str:
    if spec_id.startswith("001"):
        return "m-1"
    if spec_id.startswith("003"):
        return "m-2"
    return "m-0"


def main() -> int:
    parser = argparse.ArgumentParser(description="Import speckit tasks.md into backlog/tasks")
    parser.add_argument("--feature-dir", required=True, help="Path to specs/<id>")
    parser.add_argument(
        "--include-done",
        action="store_true",
        help="Also create backlog cards for already-checked speckit tasks",
    )
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    root = repo_root()
    feature_dir = pathlib.Path(args.feature_dir)
    if not feature_dir.is_absolute():
        feature_dir = root / feature_dir
    tasks_md = feature_dir / "tasks.md"
    if not tasks_md.is_file():
        print(f"missing {tasks_md}", file=sys.stderr)
        return 1

    spec_id = feature_dir.name
    rows = parse_tasks_md(tasks_md)
    if not rows:
        print(f"no checkbox tasks in {tasks_md}", file=sys.stderr)
        return 1

    tasks_dir = root / "backlog" / "tasks"
    tasks_dir.mkdir(parents=True, exist_ok=True)
    _, by_source, ids = load_board(tasks_dir)
    created: list[str] = []
    updated: list[str] = []
    stamp = now_stamp()
    next_ordinal = (len(ids) + 1) * 1000

    for task_id, title, done in rows:
        status = "Done" if done else "To Do"
        key = (spec_id, task_id)
        existing = by_source.get(key)
        if existing:
            if args.dry_run:
                updated.append(f"{existing.name} ({status})")
                continue
            text = replace_status_and_title(existing.read_text(encoding="utf-8"), title, status, stamp)
            existing.write_text(text, encoding="utf-8", newline="\n")
            updated.append(f"{existing.name} -> {status}")
            continue

        if done and not args.include_done:
            continue

        rf_id = next_rf_id(ids)
        ids.append(rf_id)
        filename = slugify(rf_id, spec_id, task_id)
        path = tasks_dir / filename
        body = card_text(
            rf_id=rf_id,
            title=title[:80],
            status=status,
            spec_id=spec_id,
            task_id=task_id,
            description=title,
            created=stamp,
            updated=stamp,
            milestone=milestone_for(spec_id),
            ordinal=next_ordinal,
            token_limit=8000,
        )
        next_ordinal += 1000
        if args.dry_run:
            created.append(f"{filename} ({status})")
            continue
        path.write_text(body, encoding="utf-8", newline="\n")
        by_source[key] = path
        created.append(f"{rf_id} {filename} ({status})")

    print(f"spec={spec_id} created={len(created)} updated={len(updated)}")
    for line in created:
        print(f"  created {line}")
    for line in updated:
        print(f"  updated {line}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
