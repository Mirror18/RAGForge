# ADR-0012：持久化 BM25 候选索引

- Status: Accepted
- Date: 2026-08-30

## Context

生产检索路径不能依赖 `InMemoryBm25CandidateStore`：服务重启会丢失 lexical 状态，且重新摄取与当前向量索引版本可能脱节。现有摄取流程已经以 `space_id` 和 `index_version_id` 隔离 Qdrant collection，并从 PostgreSQL 的 active revision、chunk 与对象存储事实重建候选索引。本决策不增加数据库迁移。

## Decision

- 采用 Qdrant candidate point payload 作为 durable lexical fact。worker 在完成 parsed-text、chunk 字符范围和 SHA-256 校验后，将内部 searchable text 与现有 provenance payload 一起写入对应的版本化 collection。
- server 使用 `DurableBm25CandidateStore` 替换生产组件路径中的 `InMemoryBm25CandidateStore`。每次 BM25 查询从 Qdrant scroll 读取该 collection 的 payload，在内存中重建文档频率、长度统计和 BM25 分数；内存只保存单次查询的派生状态。
- 查询、payload 校验和结果返回均强制包含 `space_id` 与 `index_version_id`。collection 名称由二者确定，Qdrant filter 同时约束二者；不同空间或版本的 point 不得参与同一次 lexical 统计。
- 重建以一个完整的、已验证的 candidate collection 为边界。新 collection 构建失败或校验失败时不发布 active pointer；回滚到旧版本只恢复旧 active index pointer 并保留旧 collection，server 随后自然读取旧版本的 lexical payload。废弃 collection 按既有索引保留策略清理。

## Consequences

- 服务重启后 BM25 状态由持久化 Qdrant payload 重建并继续命中，不需要热身文件或额外数据库表。
- lexical 查询依赖本地 Qdrant 可用性，并需滚动读取版本内 point；大 collection 会增加查询内存和延迟，后续可在保持 contract 的前提下引入持久化倒排 provider。
- searchable text 位于内部 Qdrant payload，不进入 API、trace 或 citation；其完整性由 `text_hash` 和 worker 的 parsed-text slice 校验保障。
- 旧的 `InMemoryBm25CandidateStore` 保留为单元测试 fixture，不再注册为 Spring production bean。没有新增 migration，也没有云路由静默回退。

## Alternatives

- 不采用新增 PostgreSQL 倒排表：会引入本卡禁止的 schema migration 与重复事实，且不能改善当前 Qdrant candidate collection 的版本边界。
- 不采用本地磁盘索引文件：多实例部署、容器替换和备份恢复难以保持与 active index pointer 的一致性。
- 不引入第三方 BM25 服务或源码：当前阶段保留官方依赖优先和许可证审查边界。

## References

- [ADR-0003：PostgreSQL、Qdrant、RabbitMQ 与 Valkey](0003-postgresql-qdrant-and-messaging.md)
- [ADR-0006：版本化摄取和索引原子发布](0006-versioned-ingestion-and-indexes.md)
- [检索与问答架构](../RETRIEVAL_AND_CHAT.md)
- DurableBm25CandidateStore、QdrantIndexWriter 与重启测试证据
