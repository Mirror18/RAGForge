# Phase 3 Ingestion Contracts

本目录是后续 Java persistence、Outbox/worker、connector 和 parser 实现的唯一公共依据。当前合同状态为 `planned`，`implementationStatus=contract` 表示已冻结接口和语义，不表示运行时已经实现；阶段 checklist 仍由主 Agent 管理，本目录不勾选验收项。

## SourceConnector v1

机器可读合同见 [`source-connector.v1.contract.json`](source-connector.v1.contract.json)，领域对象见 [`ingestion-domain.v1.schema.json`](ingestion-domain.v1.schema.json)。实现必须提供如下语义：

```text
discover(checkpoint, rules) -> SourceChangeSet
fetch(sourceRef, expectedVersion) -> ContentStream + SourceMetadata
commitCheckpoint(changeSet, result) -> NewCheckpoint
```

线上的 change kind 只有 `ADD`、`MODIFY`、`MOVE`、`DELETE`、`UNCHANGED` 五种。`ADDED` 等旧文字只作为迁移提示，不得作为 v1 wire value。路径先归一化为相对、`/` 分隔的 canonical source path，再执行 include/exclude；exclude 优先。稳定 source object identity 不使用 basename，因此同名不同目录不会碰撞。来源只读，`spaceId` 是每个对象和引用的隔离边界。

`credentialRef` 只允许存在于 secret resolver 返回的 connector-private configuration 中。它不得进入 API response、event payload、checkpoint、structured log、retry 或 DLQ body；日志只能记录 `credentialConfigured` 和稳定 ID。

## Versioned ingestion state

`SourceDocument` 保持逻辑身份，`DocumentRevision`、`Artifact`、`ParseReport`、`PipelineVersion`、`IngestionJob`、`JobAttempt`、`PipelineStepExecution` 和 `ActivePointer` 都是带 `spaceId`、稳定 ID、版本及 provenance 的对象。revision/artifact/pipeline version 不原地覆盖；active pointer 只指向成功且不可变的 revision。

checkpoint 只有在 revision、artifact、parse report、active pointer 决策和 outbox 状态都持久化成功后才推进。parser、object storage、OCR、database 或消息失败都必须保持旧 checkpoint 和旧 active pointer，并通过失败状态进入 retry/DLQ 观察面。`UNCHANGED` 可以不创建新 revision，但仍须完整提交 change set 后才可推进 checkpoint。

## Parser/OCR

`ParseReport` 只保存 MIME、页数、character/token 计数、native/OCR 页数、parser/version、耗时、warnings/errors 和 artifact reference。原生解析优先；低质量或扫描页才触发 OCR。image-only PDF 在 OCR 不可用时必须为 `OCR_UNAVAILABLE` 或 `BLOCKED`，不得以空文本或伪造文本标记成功；OCR 成功必须记录来源 artifact、页码、引擎版本、触发原因和审计状态。

## Outbox → RabbitMQ → worker

见 [`outbox-worker.v1.contract.json`](outbox-worker.v1.contract.json) 和 [`../events/ingestion.job.status.changed.v1.schema.json`](../events/ingestion.job.status.changed.v1.schema.json)。投递保证是 at-least-once，consumer 必须按 `spaceId + jobId + attemptId + stepName + idempotencyKey` 幂等；本合同明确不声称 exactly-once。retry 使用有限次数的指数退避，耗尽或永久失败进入 DLQ，DLQ 只带受限引用、错误码和 trace identity。

实现状态应在实现侧单独记录为 `planned`、`in-progress` 或 `implemented`；合同测试只证明文件可解析、对象/字段/拒绝语义和 fixtures 可验证，不把“字段存在”冒充运行时验收。
