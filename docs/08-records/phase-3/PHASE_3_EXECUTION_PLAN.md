# Phase 3 执行计划与所有权表

- 目标阶段：Phase 3 版本化摄取流水线
- 记录日期：2026-08-13
- 统一 base SHA：`14910c1ba4d8a1e12c2906a5658db05531633d9f`
- 主分支：`main`
- 主 Agent：Orchestrator
- 允许的外部写入：main 本地验证通过后 push 到现有 `origin`，仅用于触发 GitHub Actions；禁止 force-push、改写历史、创建 release。

## 依赖顺序

```text
P3-A checklist/contract
  -> P3-B OpenAPI/event/SourceConnector contract
  -> P3-C Flyway/repository/versioned state
  -> P3-D Outbox/RabbitMQ/worker/idempotency/DLQ
  -> P3-E file/local-directory/Git connectors
  -> P3-F parser/Parse Report/object storage/OCR
  -> P3-G cross-platform/fault/security/performance acceptance
  -> P3-H records/CI/push/phase closure
```

前置依赖未合入前，后续 Agent 只能读接口草案，不得自行发明字段、事件、migration version、object key 或 checkpoint 语义。

## 任务所有权

| Task | 目标 | 允许写入 | 只读依赖 | 单一 owner | 关键验收 | 不负责 |
|---|---|---|---|---|---|---|
| P3-A | checklist、验收口径、执行记录 | `docs/03-delivery/PHASE_3_CHECKLIST.md`、`docs/08-records/phase-3/` | Phase 3 PRD/ADR/安全/质量文档 | 主 Agent | checklist 未勾选、量化门槛明确、所有权表提交 | 任何运行时代码 |
| P3-B | SourceConnector、ingestion REST、event schema | `contracts/`、指定 `apps/server`/shared contract 目录、contract tests | P3-A、Phase 2 contract | Contract Agent | schema parse、space/version/correlation/sensitive-field tests | migration、consumer实现 |
| P3-C | 版本化 Source/Revision/Artifact/Job/Attempt/Step/Checkpoint 持久化 | `apps/server/src/main/resources/db/migration/` 单一序列、source/ingestion repository | P3-B | Persistence Agent | PostgreSQL migration、FK、状态机、space isolation、rollback tests | Rabbit topology、connector、parser |
| P3-D | Outbox relay、RabbitMQ topology、Worker consumer、retry/DLQ/幂等 | `apps/ingestion-worker`、指定 shared messaging 文件、worker tests | P3-B、P3-C | Worker Agent | Testcontainer redelivery/concurrency、ack boundary、DLQ redaction、checkpoint safety | connector parser、根 Compose |
| P3-E | file/local-directory/Git read-only connector 和 canonical path | `apps/ingestion-worker` connector 包、connector unit/acceptance tests | P3-B、P3-C | Connector Agent | full/incremental add/modify/move/delete/unchanged、duplicate basename、Git SHA、Windows/Linux | parser依赖、Web connector |
| P3-F | parser、Parse Report、object key/storage adapter、OCR seam | parser/ingestion owned modules、synthetic fixtures、quality tests | P3-B、P3-C、P3-E；新增依赖需主 Agent 审核 | Parser Agent | 6 formats、image-only PDF、OCR trigger/report、upload safety、SBOM | Chunk/index/retrieval |
| P3-G | 跨平台、故障注入、安全、性能和 CI acceptance | `tests/`、`.github/workflows/` 由主 Agent分配具体文件 | P3-C 至 P3-F 已合入 | 主 Agent + bounded test owners | Windows/Linux manifest equality、20 redelivery、fault matrix、secret/license gates | 改写生产 contract/migration |
| P3-H | 阶段记录、风险、追溯、retrospective、push和闭环 | `PROJECT_STATUS.md`、`RISK_REGISTER.md`、`TRACEABILITY_MATRIX.md`、retrospective | 全部证据 | 主 Agent | checklist全勾、main全量通过、GitHub Actions成功、worktree clean | 新功能实现 |

## 并行规则

- P3-B、P3-C、P3-D、P3-E、P3-F 不可在未满足依赖时并行写同一合同、migration、事件或公共接口。
- 最多同时 3 个执行 Agent，且每个 Agent 一个分支、一个 worktree、一个明确写入范围。
- migration 版本由 P3-C 单一 owner 顺序分配；若需要追加 migration，仍回到 P3-C owner。
- 根 `pom.xml`、Compose、依赖锁定、BOM、Phase 3 checklist 和阶段记录归主 Agent；worker 只提出精确修改建议。
- 测试数据只能是合成 fixture 或已登记可再分发样本；不读取真实个人 Obsidian vault。

## 集成顺序

每批只合并一个分支，使用 `git merge --no-ff` 和中文 Conventional Commit。每次合并后至少运行受影响的 contract、unit、Maven integration、security 和 migration checks；P3-D 之后必须运行 RabbitMQ Testcontainer；P3-F 之后必须运行 parser quality 和 secret/license checks。只有主干验证通过且 worker worktree clean 才能删除 worktree/branch。
