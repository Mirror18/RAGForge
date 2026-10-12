---
name: speckit-to-backlog
description: Import speckit tasks.md checkboxes into backlog/tasks. Use after /speckit-tasks, when a specs/*/tasks.md file changed, or when the user asks to put speckit work on the board.
---

# Speckit → Backlog

`specs/<id>/tasks.md` is a draft. Execution lives in `backlog/tasks`.

## Steps

1. Identify `FEATURE_DIR` (for example `specs/001-knowledge-loop`).
2. From the repository root run:

```powershell
python scripts/dev/import_speckit_tasks.py --feature-dir <FEATURE_DIR>
```

3. Report created vs updated card IDs. Do not implement until open cards exist on the board.
4. `/speckit-implement` then works those cards card-by-card and commits after each Done card.

Default: only unchecked `Tnnn` rows become new cards. Pass `--include-done` to also materialize checked rows. Matching `source_spec` + `source_task` always updates in place.
