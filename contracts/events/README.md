# Event Schemas

Phase 1 建立 JSON Schema/AsyncAPI 形式的 envelope 和首批 ingestion/outbox events。事件名包含 major version，例如 `ingestion.job.requested.v1`。Phase 3 在保留这些兼容事件的基础上增加统一状态事件。

当前首批文件：

- [`event-envelope.v1.schema.json`](event-envelope.v1.schema.json)：Transactional Outbox 持久化记录和 RabbitMQ 发布消息共用的 envelope。
- [`ingestion.job.requested.v1.schema.json`](ingestion.job.requested.v1.schema.json)：请求版本化摄取 Job，只携带 artifact reference。
- [`ingestion.job.completed.v1.schema.json`](ingestion.job.completed.v1.schema.json)：摄取完成摘要，只携带计数、版本 ID 和受限失败元数据。
- [`ingestion.job.status.changed.v1.schema.json`](ingestion.job.status.changed.v1.schema.json)：统一表达 `REQUESTED`、`COMPLETED`、`FAILED`、`RETRY_SCHEDULED` 和 `DLQ`，带 job/attempt/step 幂等身份、delivery attempt、受限失败和 side-effect/checkpoint 观察结果。

Payload 不包含二进制、Secret、凭据（包括 `credentialRef`）、完整文档或完整解析文本，使用 artifact reference。消费者必须幂等，schema 兼容由 contract test 验证。事件 envelope 的 `spaceId` 是安全边界的一部分，producer 和 consumer 都不得把它当作可选过滤条件。

Outbox 在 API/domain transaction 中持久化，relay 和 RabbitMQ 使用 at-least-once；worker 只能在持久化副作用、幂等记录和 checkpoint 决策完成后 ACK。合同明确不声称 exactly-once。retry 使用有限次数的指数退避，永久失败或重试耗尽发布统一 `DLQ` 状态；DLQ body 只能包含引用、错误码、有限消息和 correlation/causation/event identity。
