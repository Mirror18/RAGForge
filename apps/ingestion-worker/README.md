# RAGForge Ingestion Worker

独立部署的 Java worker 消费 RabbitMQ 摄取任务，执行版本化摄取流水线。worker 不复用 server Controller 或 repository；业务副作用通过明确的 `IngestionSideEffectHandler` 接口接入，并在 PostgreSQL 的 `ingestion_idempotency` 表中记录完成结果。

## 消息边界

- durable direct exchange：`ragforge.ingestion`
- 请求队列：`ragforge.ingestion.jobs`，路由键 `ingestion.job.requested.v1`
- 延迟重试队列：`ragforge.ingestion.jobs.retry`，使用消息过期时间并回投请求队列
- 死信队列：`ragforge.ingestion.jobs.dlq`
- 状态队列：`ragforge.ingestion.status`
- 每个环境必须通过配置使用独立的 exchange、queue 和 DLQ 名称；测试不得共享可变拓扑。

投递语义是 at-least-once，明确不宣称 exactly-once。worker 以 `(space_id, job_id, attempt_id, step_name, idempotency_key)` 作为幂等边界，并使用事务内 PostgreSQL advisory lock 串行化相同身份的并发投递。只有副作用处理和幂等记录都提交后才 ACK；解析失败、空间边界失败和策略失败进入 DLQ，不会无限重试。

重试最多 20 次，采用有上限的指数退避和确定性抖动；失败事件只发布引用、失败分类、脱敏且长度受限的消息和 trace identifiers。凭据、secret、密码、token、原始文档和全文不得进入消息、日志或 DLQ。

worker 需要访问 PostgreSQL 以读取 Phase 3 迁移创建的摄取表。worker 不负责执行 Flyway migration；数据库 schema 由 server 的迁移序列统一管理。
