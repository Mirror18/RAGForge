---
id: doc-3
title: 任务与文档工作流
type: guide
created_date: '2026-10-12 16:20'
updated_date: '2026-10-12 16:20'
---
# 任务与文档工作流

## 单一来源

| 产物 | 位置 | 说明 |
|---|---|---|
| 要执行的工作 | `backlog/tasks` | 唯一任务板。卡片状态决定下一步 |
| 现行说明 | `backlog/docs` | 按代码写。与代码冲突时以代码为准并改文档 |
| 功能规格 | `specs/<id>/spec.md` 与 `plan.md` | speckit 设计稿，不是执行板 |
| speckit 任务草案 | `specs/<id>/tasks.md` | 生成后必须导入 backlog 才能开工 |
| Worker 一次性合同 | `.agents/tickets/` | 并行 worker 才需要；日常单卡可直接按 backlog 卡执行 |

不要恢复已删除的 `docs/00–15` 长文，也不要把 `PROJECT_STATUS.md`、`TASK_BOARD.md` 当作现行文件。

## speckit 入板

1. `/speckit-specify` → `/speckit-plan` → `/speckit-tasks` 仍写在 `specs/<id>/`。
2. `tasks.md` 一经生成或更新，立刻运行：

```powershell
python scripts/dev/import_speckit_tasks.py --feature-dir specs/<id>
```

3. 脚本按 `T001` 等稳定 ID 幂等写入 `backlog/tasks`。默认只为未勾选任务建卡；已存在的 `source_spec` + `source_task` 会同步勾选状态。历史已完成项需要 `--include-done` 才会建卡。
4. `/speckit-implement` 只实现 **backlog 里 To Do / In Progress** 的对应卡，而不是直接把 `tasks.md` 当执行队列。
5. GitHub Issue 不是本仓库的任务板。`/speckit-taskstoissues` 仅在明确要求对外跟踪时使用。

## 完成后提交

每张卡验收通过后立即提交，再开始下一张。提交范围只含本卡文件。

```text
<type>(<scope>): <中文摘要>
```

正文写动机、验证、风险、回滚。禁止把多张无关卡打进一次提交。

## 卡片字段

新建或导入的卡至少包含：`id`、`title`、`status`、`labels`、`milestone`、`priority`、`token_limit`、`source`（`backlog` 或 `speckit`）。来自 speckit 的卡还要有 `source_spec`、`source_task`。

验收标准写在卡片的 Acceptance Criteria。不要在聊天里另写一套。
