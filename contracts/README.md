# Contracts

跨进程和对外契约的事实来源：

- `openapi/`：REST/SSE snapshot、error schemas、service token scopes，以及 Phase 3 space-scoped source/sync/document/revision projections。
- `events/`：RabbitMQ event envelope 与 versioned payload schemas；Phase 3 统一 ingestion status 事件覆盖 retry/DLQ 观察语义。
- `ingestion/`：SourceConnector SPI、版本化 ingestion domain、checkpoint safety、parser/OCR report、Outbox/worker 语义，以及 Phase 4 的 chunking domain 与 index version 生命周期。
- `retrieval/`：Phase 4 不可变 RetrievalProfileVersion（dense/BM25/RRF/rerank/expansion 参数）。
- `answer/`：Phase 5 v1 Answer、Claim、Citation、Abstention public schemas；citation token 只允许当前 Evidence Bundle 的 `evidence_id`。
- `agent/`：Phase 5 v1 只读 `ToolCall`/`ToolResult` schemas，固定工具 allow-list、空间边界、幂等和敏感审计字段禁止规则。
- `events/answer.sse.v1.schema.json`：Phase 5 answer SSE v1 event union；仅新增 answer SSE schema，不改变既有 run/ingestion event 语义。

Java/Python 实现必须以这里的 JSON/Markdown 合同为准。

契约先于实现修改；CI 检查 lint、breaking changes 和生成代码一致性。当前 Phase 3 文件均为 `planned`/`contract`，不代表生产实现已完成，也不替代阶段 checklist。内部 Java 模块的普通方法不需要被错误地建成远程契约。
