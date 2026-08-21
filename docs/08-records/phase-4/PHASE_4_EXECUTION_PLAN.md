# Phase 4 执行计划与所有权表

- 目标阶段：Phase 4 Chunk Studio、索引与检索
- 记录日期：2026-08-15
- 统一 base SHA：`e27ae75f085e3a4fe3d39fa0b00b5ceecb931bd2`
- 主分支：`main`
- 主 Agent：Orchestrator
- 允许的外部写入：main 本地验证通过后 push 到现有 `origin`，仅用于触发 GitHub Actions；禁止 force-push、改写历史、创建 release。
- 技术基线：Java 21 + Spring Boot 3.5.x，维持 [ADR-0002](../../02-architecture/adr/0002-java-ai-version-baseline.md) 已验收基线；本阶段**不**升级 Java/Boot，也不引入未登记的第三方源码。

## 依赖顺序

```text
P4-A checklist/contract
  -> P4-B chunk/index/retrieval contract
  -> P4-C Flyway/repository/状态机
  -> P4-D 父子分块引擎
  -> P4-E embedding 缓存 + Qdrant index version
  -> P4-F 检索服务（dense/BM25/RRF/rerank/parent expansion）
  -> P4-G Chunk Studio / Retrieval Playground
  -> P4-H 评估/规模/CI/记录/阶段闭环
```

前置依赖未合入前，后续 Agent 只能读接口草案，不得自行发明字段、migration version、Qdrant collection 命名或 retrieval profile 语义。

## 任务所有权

| Task | 目标 | 允许写入 | 只读依赖 | 单一 owner | 关键验收 | 不负责 |
|---|---|---|---|---|---|---|
| P4-A | checklist、验收口径、执行记录 | `docs/03-delivery/PHASE_4_CHECKLIST.md`、`docs/08-records/phase-4/` | ROADMAP Phase 4、ADR-0003/0006、[INGESTION_PIPELINE](../../02-architecture/INGESTION_PIPELINE.md)、[RETRIEVAL_AND_CHAT](../../02-architecture/RETRIEVAL_AND_CHAT.md)、[RAG_EVALUATION](../../04-quality/RAG_EVALUATION.md) | 主 Agent | checklist 未勾选、量化门槛明确（Recall@10>=0.90、MRR@10>=0.75）、所有权表提交 | 任何运行时代码 |
| P4-B | chunk/index/retrieval 领域契约与 chunk-studio/playground REST 投影 | `contracts/`（chunking-domain、index-version、retrieval-profile）、contract tests | P4-A | Contract Agent | schema parse、space/version/correlation、override 状态机、profile 不可变、敏感字段禁止 | migration、consumer 实现 |
| P4-C | ParentChunk/ChildChunk/ChunkOverride/IndexVersion/RetrievalProfile 持久化与状态机 | `apps/server/src/main/resources/db/migration/` 单一序列（V9 起）、chunk/index/profile repository | P4-B | Persistence Agent | PostgreSQL migration、FK、状态机、space isolation、active pointer 单行、rollback tests | Qdrant、检索实现 |
| P4-D | 父子分块引擎：标题/表格/代码/列表边界、token 估算、引用锚点、overlap | chunking 模块、合成 fixture、chunking unit/quality tests | P4-B、P4-C | Chunking Agent | 边界不硬切、parent/child 范围与锚点可验证、确定性、Windows/Linux 一致 | embedding、索引写入 |
| P4-E | embedding cache、Qdrant candidate index、VALIDATING 校验、active pointer 发布、24h 保留 | index/embedding 模块、Qdrant Testcontainer tests、collection/object key 命名 | P4-B、P4-C、P4-D | Index Agent | 候选隔离、校验失败不污染 ACTIVE、发布原子、旧索引保留、空间过滤、幂等 cache key | 检索排序、Chunk Studio |
| P4-F | 检索服务：dense+BM25+RRF+rerank+parent expansion、RetrievalProfileVersion、Evidence Bundle | retrieval 模块、retrieval unit/integration tests、30 问评估切片 | P4-B、P4-C、P4-E | Retrieval Agent | 空间过滤强制、profile 不可变、RRF/rerank 可复现、evidence 含 provenance、离线评估对比 | Web、生成回答 |
| P4-G | Chunk Studio 与 Retrieval Playground（REST + Web） | 指定 REST controller、`apps/web` 页面、前后端契约测试 | P4-B、P4-C、P4-D、P4-E、P4-F | Studio Agent | override 可审计、NEEDS_REVIEW 流转、A/B 展示、按角色权限 | 离线评估 |
| P4-H | 30 问基准、100 万 child chunk 规模证据、跨平台 acceptance、CI、记录、闭环 | `scripts/phase4/`、`tests/evidence/`、`.github/workflows/`、阶段记录 | 全部证据 | 主 Agent | checklist 全勾、main 全量通过、GitHub Actions 成功、规模证据可复现、worktree clean | 新功能实现 |

## 并行规则

- P4-C、P4-D、P4-E、P4-F 在依赖未满足时不可并行写同一 contract、migration、collection 命名或公共接口。
- 最多同时 3 个执行 Agent，且每个 Agent 一个分支、一个 worktree、一个明确写入范围。
- migration 版本由 P4-C 单一 owner 顺序分配；追加 migration 仍回到 P4-C owner。
- 根 `pom.xml`、Compose、依赖锁定、BOM、Phase 4 checklist 和阶段记录归主 Agent；worker 只提出精确修改建议。
- 测试数据只能是合成 fixture 或已登记可再分发样本；不读取真实个人 Obsidian vault。
- 检索/分块/embedding/rerank 变更必须附带离线评估对比并记录配置版本（[AGENTS.md](../../../AGENTS.md) 质量门禁）。

## 集成顺序

每批只合并一个分支，使用 `git merge --no-ff` 和中文 Conventional Commit。每次合并后至少运行受影响的 contract、unit、Maven integration、security 和 migration checks；P4-E 之后必须运行 Qdrant Testcontainer；P4-F 之后必须运行检索评估对比；P4-G 之后必须运行前端门禁。只有主干验证通过且 worker worktree clean 才能删除 worktree/branch。

## 执行状态（2026-08-21）

| Task | 状态 | 提交/证据 |
|---|---|---|
| P4-A | 完成 | Phase 4 验收口径、所有权和退出条件已记录 |
| P4-B | 完成并合入 | `fed0034`：Phase 4 OpenAPI/Chunk Studio/Playground contract；contract 42/42 |
| P4-C | 完成并合入 | V9/V10 migration、chunk/index/profile repository、状态机与 PostgreSQL 6/6 |
| P4-D | 完成并合入 | `300569b`：父子分块、专用边界策略、token/anchor 确定性测试 |
| P4-E | 完成并合入 | `ab81ed1`：embedding cache、Qdrant candidate index、candidate validation/active pointer |
| P4-F | 完成并合入 | `1f6450e`：dense/BM25/RRF/rerank/parent expansion；trace hardening `c1d53f3` |
| P4-G | 完成并合入 | `041bf34` + `e27ae75`：Chunk Studio、Retrieval Playground、override ref 持久化与 Web 修复 |
| P4-H | 完成 | `phase4-retrieval-benchmark.json`、`phase4-1m-qdrant.json`、`phase4-isolation-and-override.json`；CI Phase 4 gate 已接入 |

## 阶段量化门槛（来自 [RAG_EVALUATION](../../04-quality/RAG_EVALUATION.md) §4.1 与 ROADMAP Phase 4 退出条件）

- P4-EXIT-01：30 问检索基准 `Recall@10 >= 0.90`、`MRR@10 >= 0.75`（版本化记录 dataset/index/profile 配置）。
- P4-EXIT-02：100 万 child chunk 数据量下检索 p95 时延与召回目标有可复现证据。
- P4-EXIT-03：空间过滤、索引切换与回滚测试全部通过。
- P4-EXIT-04：override 冲突测试证明源更新后旧 override 不静默覆盖。

## P4-H 验收证据（2026-08-21）

- 30 问固定切片 `q-001..q-030`：Recall@10 `0.965517`、MRR@10 `0.827586`，29 个有允许 evidence reference 的 case 参与数值指标，2 个 abstention/security probe 单独记录；forbidden source leak `0`。
- Qdrant `v1.11.5` 1M synthetic child points：4 个空间、`space_id + index_version` filter、Recall@10 `1.0`、p95 `1101.3382 ms`、p99 `1206.9538 ms`；原始 JSON 不含生产数据。
- 空间过滤、candidate index 发布/失败回退/24 小时保留、override 源 revision 冲突与状态机：targeted Maven 17/17，0 failures/errors；证据见 `tests/evidence/phase4-isolation-and-override.json`。
- 根 `mvn --batch-mode --no-transfer-progress test`：BUILD SUCCESS；Server 与 ingestion-worker 均无失败；Python contract 42/42；web format/build、format、architecture、link、secret、dependency inventory 和 Compose checks 通过。
