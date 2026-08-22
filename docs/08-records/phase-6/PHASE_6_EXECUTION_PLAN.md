# Phase 6 执行计划与所有权表

- 目标阶段：Phase 6 评估、观测、安全与恢复
- 记录日期：2026-08-22
- 统一 base SHA：`0fe22db5979aa5ae7892165c227a5c8a484bdfb9`
- 主分支：`main`
- 主 Agent：Orchestrator
- 技术基线：Java 21 + Spring Boot 3.5.x；模块化单体 + 独立 ingestion worker
- 高风险边界：不启用云出境、不执行生产迁移、不接受新 ADR/许可证、不创建 release

## 依赖图

```text
P6-A contracts/checklist/evidence schema
  -> P6-B 120+ evaluation and red-team
  -> P6-C OTel/metrics/dashboard/alerts/runbooks
  -> P6-D security convergence
  -> P6-E isolated backup/restore
  -> P6-F real embedding capacity and mixed load
  -> P6-G retention/deletion/audit/cost/SSE cleanup
  -> P6-H global verification and phase closure
```

P6-B、P6-C、P6-E 可在文件 ownership 不冲突时并行；P6-D 依赖 A 与威胁模型复核；P6-F 依赖固定运行配置、embedding dimension 和数据集；P6-G 若涉及 migration 或共享 Run 模型由主 Agent 单一 owner 串行；P6-H 最后执行且不增加新业务功能。

## 任务所有权

| Task | 目标 | 允许写入 | 只读依赖 | Owner | 必跑验证 | 不负责 |
|---|---|---|---|---|---|---|
| P6-A | 冻结 checklist、计划、证据 schema、风险入口 | `docs/03-delivery/PHASE_6_CHECKLIST.md`、`docs/08-records/phase-6/`、Phase 6 contract/evidence schema | Phase 5 records、RAG evaluation、performance/security docs | 主 Agent | Markdown/link/schema checks | runtime feature |
| P6-B | 120+ dataset、人工复核、baseline/candidate、Promptfoo matrix/red-team adapter | `tests/evaluation/phase6*`、`scripts/phase6/evaluation*`、`tests/evidence/phase6-evaluation*` | Phase 5 dataset/schema、ADR-0008、test data policy | Evaluation Agent | dataset validator、evaluation/security tests | telemetry/backup |
| P6-C | OTel、metrics、dashboards、alerts、runbook 可执行证据 | `deploy/compose/` observability profile、`docs/05-operations/`、`tests/evidence/phase6-observability*` | OBSERVABILITY、deployment、runbook rules | Observability Agent | compose/health/smoke/trace redaction | migrations/evaluation |
| P6-D | 上传/SSRF/越权/注入/供应链安全收敛 | security tests, fixtures, threat review, `scripts/phase6/security*` | SECURITY_BASELINE、THREAT_MODEL、Phase 5 tool tests | Security Agent | targeted security, secret/SCA/SBOM | new business permissions |
| P6-E | PG/Object/Qdrant backup and isolated recovery drills | `scripts/phase6/recovery*`、`tests/evidence/phase6-recovery*`、operations docs | BACKUP_RESTORE、migration/schema、compose | Recovery Agent | isolated restore and hash/count verification | production data |
| P6-F | 真实 embedding dimension、1M chunks、并发/混合负载/成本 | `scripts/phase6/capacity*`、`tests/evidence/phase6-capacity*`、performance docs | fixed P6 config, Qdrant/Retrieval ports, PERFORMANCE_PLAN | Performance Agent | capacity/perf and no-synthetic-proxy checks | model/route architecture |
| P6-G | retention/delete jobs、audit export、cost report、SSE cleanup | one migration sequence, owned platform modules, tests/evidence | DATA_EGRESS_AND_RETENTION、Run/Event model、P6-C | 主 Agent | migration/integration/security/ops tests | unrelated API redesign |
| P6-H | full gates, records, risk/traceability/retrospective/CI closure | `PROJECT_STATUS.md`、`RISK_REGISTER.md`、`TRACEABILITY_MATRIX.md`、retrospective | all P6 evidence and merged commits | 主 Agent | full local + GitHub Actions | new runtime behavior |

## 分支与 worktree

每个 worker 从本计划记录的 base SHA 创建 `codex/p6-<task>-<agent>` 分支和 `D:\project\learning\RAGForge-worktrees\<branch-slug>` worktree。每个任务使用独立 Compose project、端口、build/cache 和可变测试数据；worker 只能修改 owner 范围，完成时必须提交中文 Conventional Commit 并回报完整测试证据。

## 版本与证据约定

- Dataset、evaluation run、configuration、prompt/model/retrieval/index、dashboard/runbook 和 recovery fixture 均有 immutable version/hash。
- 证据 JSON 不保存原始客户 prompt、原文、凭据、Cookie、Authorization header 或完整 provider body。
- 每次容量/恢复演练记录 commit、机器、容器镜像、资源限制、seed、开始/结束、p50/p95/p99、错误、人工步骤和 hash。
- Promptfoo 如引入，仅作为已登记 MIT dev/CI 依赖；核心结果写入自有 Evaluation Run。Langfuse 不作为运行依赖。

## 阶段状态

| Task | 状态 | 证据/备注 |
|---|---|---|
| P6-A | completed | Checklist、执行计划和验收门槛已冻结，基线 `0fe22db` |
| P6-B | pending | 现有 Phase 5 仅 12 cases；需要 >=120 与人工/red-team |
| P6-C | pending | 当前只有设计文档，需真实可运行 dashboard/alert/runbook |
| P6-D | pending | Phase 5 安全回归存在，但 Phase 6 专项 corpus/SCA 收敛未完成 |
| P6-E | pending | 只有 backup smoke/事件 replay；完整隔离恢复未完成 |
| P6-F | pending | 现有 1M 证据为 8 维合成向量，不满足真实容量门槛 |
| P6-G | pending | retention/deletion/audit export/cost/SSE cleanup 尚未闭环 |
| P6-H | pending | 等全部门槛与 CI 证据完成后执行 |

## 阶段结论

Phase 6 已建立验收基线但尚未满足退出条件。当前最重要的证据缺口是 >=120 评估集、真实 embedding 维度容量、隔离恢复 RPO/RTO、可运行观测/告警/Runbook、专项安全/red-team 和 retention/deletion 闭环；不得用 Phase 5 单 fixture 或合成容量数据替代。
