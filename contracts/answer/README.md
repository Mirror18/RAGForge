# Answer Contracts

Phase 5 的 Answer public contracts 固定为 `v1`。字段使用 `snake_case`，所有回答和拒答均绑定 `space_id`、`correlation_id`、`run_id` 与请求 `idempotency_key`。

- `answer.v1.schema.json`：回答状态、claim、结构化 citation、abstention、tool 调用引用和检索 provenance。
- `claim.v1.schema.json`：回答 claim 与 `citation_tokens`。token 只能是当前 Evidence Bundle 中原始的 `evidence_id`，不接受 `[1]`、文件名、URL 或带前缀字符串。
- `citation.v1.schema.json`：服务端生成的 Evidence allow-list 投影，保留 revision/chunk/content reference、hash 和位置锚点，不含 quote、正文或模型生成的引用文字。
- `abstention.v1.schema.json`：可审计的结构化拒答原因，不携带 prompt、provider 原文或秘密。

契约只冻结 public shape；EvidenceBundle membership、space authorization、claim/citation 一致性和 token 去重由服务端在持久化/发送前重新验证。
