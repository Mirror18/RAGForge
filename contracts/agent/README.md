# Agent Contracts

Phase 5 v1 只允许三个只读工具：`knowledge.search`、`document.read`、`web.fetch`。`tool-call.v1.schema.json` 与 `tool-result.v1.schema.json` 固定 `space_id`、`correlation_id`、`run_id`、`sequence` 和 `idempotency_key`，并要求服务端每次调用重新鉴权。

工具输出只返回 Evidence/document reference、hash、位置锚点或受限 web reference；禁止 Shell、SQL、任意网络、外部写入、凭据、header/cookie、完整 prompt、完整文档和原始 web body。SSRF、空间成员关系、allow-list、超时和输出上限属于运行时安全门禁，不能由模型或客户端字段绕过。
