# ADR-0003：持久化、向量、消息与缓存

- Status: Accepted
- Date: 2026-08-12

## Context

系统需要强约束业务数据、可重建向量索引、可靠异步摄取、服务端 Session 和本地可部署性。

## Decision

- PostgreSQL 保存业务真相和版本记录。
- Qdrant 保存向量与检索 payload。
- RabbitMQ 传递异步任务，配合 PostgreSQL Transactional Outbox。
- Valkey 保存 Session、缓存、限流和短租约，使用 Redis 协议/API。
- S3-compatible storage 保存原文件和大型 artifacts。

## Consequences

- 组件较多，但边界和恢复职责明确。
- Qdrant 可从 PostgreSQL + Object Storage 重建。
- 不依赖跨组件分布式事务，必须实现幂等和补偿。
- Valkey 选择避免把部署策略绑定到 Redis 后续许可证变化。

## Alternatives

- 仅 PostgreSQL + pgvector：MVP 可更简单，但本项目希望专门实践向量数据库运营和过滤。
- Kafka：当前吞吐和流式保留需求不足以证明其成本。
