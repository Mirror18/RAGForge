# 需求追溯矩阵

本表连接产品需求、架构决策、验证资产和路线阶段。实施后把“计划验证”替换为具体测试 ID、CI Run 或报告链接。

| 需求 ID | 需求 | 来源 | 架构/决策 | 计划验证 | Phase |
|---|---|---|---|---|---|
| RF-IAM-001 | 本地账号、Session、CSRF | [PRD 3.1](../01-product/PRD.md#31-身份与权限) | [ADR-0007](../02-architecture/adr/0007-session-authentication.md) | auth integration + browser CSRF | 1 |
| RF-IAM-002 | 空间级 RBAC 和零泄漏 | PRD 3.1/3.2 | [ADR-0004](../02-architecture/adr/0004-space-level-rbac.md) | role matrix + cross-space security | 1–6 |
| RF-SRC-001 | upload/local/Git/web connectors | PRD 3.3 | [Ingestion](../02-architecture/INGESTION_PIPELINE.md) | connector contracts + E2E | 3/5 |
| RF-SRC-002 | 只读增量同步与 checkpoint | PRD 3.3 | ADR-0006 | add/modify/move/delete/redelivery | 3 |
| RF-ING-001 | 版本化可观察 Pipeline | PRD 3.4 | ADR-0006 | job/step provenance integration | 3 |
| RF-ING-002 | 原生解析 + OCR fallback | PRD 3.4 | Ingestion 5 | parser/OCR corpus | 3 |
| RF-ING-003 | 父子分块与 override | PRD 3.4/3.8 | Ingestion 6/8 | chunk golden + override conflict | 4 |
| RF-IDX-001 | Candidate index 原子发布 | PRD 3.4 | ADR-0006 | fault injection + rollback | 4 |
| RF-RET-001 | dense + BM25 + RRF + rerank | PRD 3.5 | [Retrieval](../02-architecture/RETRIEVAL_AND_CHAT.md) | Recall/MRR evaluation | 4 |
| RF-CIT-001 | 精确、可鉴权引用 | PRD 3.5 | Retrieval 3 | citation precision + forged ID | 5 |
| RF-ANS-001 | 证据不足拒答 | PRD 3.5 | Retrieval 4 | abstention dataset | 5–6 |
| RF-PRV-001 | Ollama + OpenAI-compatible | PRD 3.6 | ADR-0005 | provider contract matrix | 2 |
| RF-PRV-002 | 能力声明和连接测试 | PRD 3.6 | [Provider model](../02-architecture/PROVIDER_AND_RUN_MODEL.md) | capability/error tests | 2 |
| RF-EGR-001 | 空间显式云端出境 | PRD 3.6 | ADR-0005 | denied/failover spy tests | 2–6 |
| RF-PRM-001 | Prompt 不可变版本 | PRD 3.6 | Provider model 5 | version/replay tests | 2 |
| RF-RUN-001 | Run/Step/SSE replay/cancel | PRD 3.7 | API/SSE + Provider model | reconnect/race/usage dedupe | 2 |
| RF-AGT-001 | 只读安全工具 | PRD 3.7 | Retrieval 6 + threat model | SSRF/schema/permission red-team | 5–6 |
| RF-EVL-001 | 120+ 可版本化评估 | PRD 4/5 | ADR-0008 | [Evaluation plan](../04-quality/RAG_EVALUATION.md) | 6 |
| RF-OBS-001 | 端到端可观测 | PRD 4 | ADR-0008 | trace continuity + dashboards | 1–6 |
| RF-PERF-001 | 目标规模和 p95 | Charter 4/5 | Performance plan | load/soak reports | 4–7 |
| RF-OPS-001 | Compose Linux 交付 | PRD 4 | [Deployment](../05-operations/DEPLOYMENT.md) | clean Ubuntu acceptance | 7 |
| RF-OPS-002 | RPO24h/RTO4h | Charter 4 | [Backup](../05-operations/BACKUP_RESTORE.md) | isolated restore drill | 6–7 |
| RF-OSS-001 | 第三方许可证可追溯 | Charter 7 | ADR-0009 | SBOM/license/notice CI | 0–7 |

## Phase 0 验收证据与 Phase 1 入口

| 证据 ID | 验收内容 | 证据文件/命令 | 后续测试 ID | Phase |
|---|---|---|---|---|
| P0-ASSET-001 | 可复现 corpus、question manifest、hash 和 image-only 边界 | [`PHASE_0_BENCHMARK_RESULTS.md`](phase-0/PHASE_0_BENCHMARK_RESULTS.md)；`python scripts/phase0/validate_assets.py --root fixtures` | `P1-EVAL-ASSET-001` | 0 → 1 |
| P0-RAGFLOW-001 | RAGFlow 独立 Compose、解析/检索/Chat、重启和资源证据 | [`PHASE_0_BENCHMARK_RESULTS.md`](phase-0/PHASE_0_BENCHMARK_RESULTS.md) | `P1-SEC-SPACE-001`, `P1-OPS-READY-001` | 0 → 1 |
| P0-ANYTHINGLLM-001 | AnythingLLM workspace、33 条回答、导入限制、重启和模型切换 | [`PHASE_0_BENCHMARK_RESULTS.md`](phase-0/PHASE_0_BENCHMARK_RESULTS.md) | `P1-CIT-PROV-001`, `P1-ING-DUP-001` | 0 → 1 |
| P0-SEC-001 | 跨空间、禁止来源、同名 provenance 和 OCR hallucination 失败证据 | [`RISK_REGISTER.md`](RISK_REGISTER.md)；q-013/q-014/q-015/q-016/q-028/q-032 | `P1-SEC-SPACE-001`, `P1-CIT-FORBIDDEN-001`, `P1-OCR-ABSTAIN-001` | 0 → 1 |
| P0-LICENSE-001 | 上游 release/tag、精确 commit、LICENSE/NOTICE 和 use mode | [`UPSTREAM_REUSE_REGISTER.md`](../07-research/UPSTREAM_REUSE_REGISTER.md)、[`THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md) | `P1-COMPLIANCE-001` | 0 → 1 |

## Phase 1 实施证据

| 证据 ID | 交付/风险 | 实现与验证 | 结果 | 后续 |
|---|---|---|---|---|
| P1-ENG-001 | Java 多模块与独立 worker 生命周期 | [`pom.xml`](../../pom.xml)、`apps/server/pom.xml`、`apps/ingestion-worker/pom.xml`；`mvn -B -ntp -DskipTests compile` | 本地通过 | P2 继续保持模块化单体 + 独立 worker |
| P1-CONTRACT-001 | OpenAPI、事件 Envelope 和幂等/错误基线 | [`contracts/`](../../contracts/)、`python scripts/ci/contract_test.py`、`tests/contract` 14/14 | 通过 | P2 先扩展 Provider/Run contract |
| P1-OPS-001 | Compose 隔离、健康和新环境启动 | [`deploy/compose/README.md`](../../deploy/compose/README.md)、`validate_compose.py`、真实项目 `ragforge-p1-api-check` | 本地通过 | Linux CI/Phase 7 固定 digest |
| P1-DATA-001 | Flyway、UUIDv7、audit/outbox/idempotency | [`apps/server/src/main/resources/db/migration/`](../../apps/server/src/main/resources/db/migration/)、`ServerIntegrationTest`、真实 PostgreSQL schema 查询 | 本地通过 | P3 扩展重投/DLQ/checkpoint |
| P1-IAM-001 | Cookie Session、CSRF、Valkey、空间 RBAC 和 no-leak | [`tests/acceptance/test_phase1_api_smoke.py`](../../tests/acceptance/test_phase1_api_smoke.py)、`ServerIntegrationTest` | 3/3 smoke；本地 Compose 黑盒通过 | P2 扩展 token/route policy |
| P1-RECOVERY-001 | PostgreSQL backup smoke 与健康探针 | [`scripts/ops/backup_smoke.py`](../../scripts/ops/backup_smoke.py)、[`scripts/ops/health_probe.py`](../../scripts/ops/health_probe.py)、实际 `.sql` + SHA-256 | 本地通过 | P6 完成隔离恢复演练 |
| P1-CI-001 | 格式、架构、秘密、链接、依赖、SBOM、Maven/npm 质量门禁 | [`.github/workflows/quality.yml`](../../.github/workflows/quality.yml)、本地 `scripts/ci/*`、`mvn -B -ntp test` | 本地全量门禁通过；Maven 8 tests/0 failures；无 remote Run | 配置 remote 后保留 Run ID/artifact |
| P1-OSS-001 | 依赖锁定和无源码复制 | [`DEPENDENCY_LEDGER.md`](phase-1/DEPENDENCY_LEDGER.md)、`dependency_inventory.py --require-lockfiles`、Phase 0 reuse register | 本地登记通过；生产扫描待 CI | 发布前重跑 SBOM/license/SCA |
