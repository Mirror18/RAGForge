# Architecture Decision Records

ADR 采用不可变历史：决策变更时新增 ADR 并把旧文档标记为 `Superseded by ADR-xxxx`，不删除原有背景。

| ADR | 状态 | 决策 |
|---|---|---|
| [0001](0001-modular-monolith-and-worker.md) | Accepted | 模块化单体 + 独立摄取 Worker |
| [0002](0002-java-ai-version-baseline.md) | Accepted | Java 21、Boot 3.5.x、Spring AI 1.1.x 基线 |
| [0003](0003-postgresql-qdrant-and-messaging.md) | Accepted | PostgreSQL + Qdrant + RabbitMQ + Valkey |
| [0004](0004-space-level-rbac.md) | Accepted | 单租户、多空间、空间级 RBAC |
| [0005](0005-provider-abstraction-and-egress.md) | Accepted | Provider 能力抽象和显式云端出境 |
| [0006](0006-versioned-ingestion-and-indexes.md) | Accepted | 版本化摄取和索引原子发布 |
| [0007](0007-session-authentication.md) | Accepted | 服务端 Session，不使用浏览器长期 JWT |
| [0008](0008-evaluation-and-llm-observability.md) | Accepted | 自有评估为准、Promptfoo CI、Langfuse 可选 |
| [0009](0009-upstream-reuse-policy.md) | Accepted | 依赖优先、源码复用登记和许可证闸门 |
| [0010](0010-phase5-provider-material-composition.md) | Accepted | Phase 5 真实 embedding、版本化 evidence material 与 session authorizer 的组合边界 |
| [0011](0011-multi-instance-run-event-fanout.md) | Accepted | 多实例 Run Event live fan-out（Valkey live hint + PostgreSQL durable replay） |
| [0012](0012-durable-bm25.md) | Accepted | 以版本化 Qdrant candidate payload 持久化并重建 BM25 lexical 状态 |
| [0013](0013-versioned-knowledge-execution.md) | Accepted | 受控知识阶段、可验证解析产物与统一检索快照；实现仍须独立契约、迁移和安全验证 |

模板必填项：Status、Date、Context、Decision、Consequences、Alternatives、References。
