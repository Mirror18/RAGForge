# Event Schemas

Phase 1 建立 JSON Schema/AsyncAPI 形式的 envelope 和首批 ingestion/outbox events。事件名包含 major version，例如 `ingestion.job.requested.v1`。

Payload 不包含二进制、Secret 或完整文档，使用 artifact reference。消费者必须幂等，schema 兼容由 contract test 验证。
