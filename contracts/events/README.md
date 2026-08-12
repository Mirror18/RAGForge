# Event Schemas

Phase 1 建立 JSON Schema/AsyncAPI 形式的 envelope 和首批 ingestion/outbox events。事件名包含 major version，例如 `ingestion.job.requested.v1`。

当前首批文件：

- [`event-envelope.v1.schema.json`](event-envelope.v1.schema.json)：Transactional Outbox 持久化记录和 RabbitMQ 发布消息共用的 envelope。
- [`ingestion.job.requested.v1.schema.json`](ingestion.job.requested.v1.schema.json)：请求版本化摄取 Job，只携带 artifact reference。
- [`ingestion.job.completed.v1.schema.json`](ingestion.job.completed.v1.schema.json)：摄取完成摘要，只携带计数、版本 ID 和受限失败元数据。

Payload 不包含二进制、Secret、凭据、完整文档或完整解析文本，使用 artifact reference。消费者必须幂等，schema 兼容由 contract test 验证。事件 envelope 的 `spaceId` 是安全边界的一部分，producer 和 consumer 都不得把它当作可选过滤条件。
