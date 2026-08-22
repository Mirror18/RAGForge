# Phase 5 执行计划与所有权表

- 目标阶段：Phase 5 带引用问答与只读 Agent
- 记录日期：2026-08-21
- 统一 base SHA：当前阶段闭环基线 `49d9160`
- 主分支：`main`
- 主 Agent：Orchestrator
- 技术基线：Java 21 + Spring Boot 3.5.x；维持模块化单体 + 独立 ingestion worker。
- 外部写入：仅允许已有 `origin` 的普通 push 触发 CI；禁止 force-push、release 和生产操作。

## 依赖图

```text
P5-A checklist/contracts/evaluation schema
  -> P5-B prompt/run persistence
  -> P5-C RAG generation orchestration
  -> P5-D citation validation/persistence
  -> P5-E SSE/reconnect/cancel/web interaction
  -> P5-F read-only tools and SSRF policy
  -> P5-G evaluation/security/performance evidence
  -> P5-H records, CI and phase closure
```

P5-F 可在 P5-A 合入后与 P5-B/P5-C 并行，但不得写同一 migration、OpenAPI operation、shared schema 或阶段记录。P5-E 依赖 P5-C/P5-D 的 answer/citation projection。P5-G 只能在行为冻结后运行。

## 任务所有权

| Task | 目标 | 允许写入 | 只读依赖 | 单一 owner | 必跑验证 | 不负责 |
|---|---|---|---|---|---|---|
| P5-A | 冻结 checklist、执行计划、Answer/Citation/Tool/SSE schema、评估 fixture 口径 | `docs/03-delivery/PHASE_5_CHECKLIST.md`、`docs/08-records/phase-5/`、`contracts/answer/`、`contracts/agent/`、`contracts/events/answer*.json`、contract tests | Phase 4 retrieval contracts、PRD、security/evaluation docs | 主 Agent | JSON/schema + contract tests | 运行时实现、migration |
| P5-B | Prompt immutable version、RAG run/step/citation/tool provenance persistence | `apps/server/src/main/resources/db/migration/V11__phase5_*.sql`、`prompt/`、`run/` persistence additions | P5-A contracts、V1–V10、ADR-0005/0008 | Persistence Agent | targeted PostgreSQL integration + unit | Web、SSRF client、评估记录 |
| P5-C/D | RAG generation、context budget、structured refusal、citation token parser/validator | `apps/server/src/main/java/com/ragforge/server/answer/`、`retrieval/` additions、server tests | P5-A/B、RetrievalService、EvidenceBundle、provider egress | Answer Agent | unit/integration/security/evaluation slice | Tool HTTP client、Web |
| P5-E | Answer SSE/replay/cancel and citation/revision API + UI | server answer controller/event projection; `apps/web/src/` answer views/API; owned contract additions only through main | P5-C/D, existing RunEventStore/SSE | Interaction Agent | MockMvc, replay/cancel, web format/build | Migration, model/provider policy |
| P5-F | knowledge.search/document.read/web.fetch, allowlist, SSRF, output caps, audit | `agent/` package、agent tests、security fixtures | P5-A, space RBAC, revision/artifact repositories, threat model | Tool Security Agent | schema, SSRF, permission, audit tests | Answer prompt, root build |
| P5-G/H | Versioned datasets, baseline/candidate report, security/performance evidence, CI and closure records | `scripts/phase5/`、`tests/evidence/`、quality workflow additions、PROJECT_STATUS/RISK/TRACE/retrospective | all merged P5 behavior and tests | 主 Agent | full local gates + GitHub Actions | New runtime behavior |

## 分支/worktree protocol

每个 worker 从本文件记录的 base SHA 创建 `codex/p5-<task>-<agent>` 分支和 `D:\project\learning\RAGForge-worktrees\<branch-slug>` worktree；独立 Maven/npm/build/cache、Compose project name、端口和测试数据。worker 只能提交自有范围，需以中文 Conventional Commit 回报 branch、worktree、base、commit、文件、命令/结果、风险和集成注意事项。

## 版本与回滚约定

- P5 新增 migration 只向前，版本号由 P5-B owner 单序列分配；旧应用在兼容窗口内可读取已有 Phase 2/4 Run。
- Answer/Citation/Tool/SSE schema 以 `v1` 发布；新增字段先 optional，禁止复用既有字段表达新语义。
- Prompt、retrieval profile、model route、index、revision、artifact 和 tool schema 通过 ID + version/hash 绑定；历史 Run 只读，不被当前 pointer 重写。
- 失败生成和安全拒答保留 Run/Step/trace/audit 版本投影；回滚只切换 approved pointer，不删除历史证据。

## 阶段状态

| Task | 状态 | 证据/备注 |
|---|---|---|
| P5-A | completed | 合同、fixture、执行口径已冻结；阶段基线为 `43aa7c1` |
| P5-B | completed | V11/V12、答案/引用/事件持久化与回滚说明；PostgreSQL 集成通过 |
| P5-C/D | completed-with-blocker | core、citation validator、typed session authorizer、provider embedding、版本化 material service、prompt hash resolver、active retrieval identity 与 opt-in production graph 完成；真实 provider route/credential 与 RAG E2E 仍缺 |
| P5-E | completed | API、严格 SSE、Last-Event-ID、cancel、citation preview、Web build 已通过 |
| P5-F | completed | 三个只读工具、SSRF、跨空间、输出上限和审计投影测试已通过 |
| P5-G/H | completed-local | 生成/性能/安全证据与本地全量门禁已通过；main server `196/196`，远端 CI 新 Run 待本次记录提交 push 后核对 |

## 阶段结论

本阶段完成了真实 material service、session authorizer 和 opt-in provider/retrieval/prompt/generation graph 的仓库内实现与本地回归，但不关闭 Phase 5：默认路径必须 fail-closed，且真实 provider route/credential 与受控 RAG E2E 证据尚未具备。该阻塞需要在 ADR-0010 人工接受前提下完成配置审查和真实环境验收。
