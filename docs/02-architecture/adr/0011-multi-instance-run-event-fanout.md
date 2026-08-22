# ADR-0011：多实例 Run Event live fan-out

- Status: Accepted
- Date: 2026-08-22

## Context

`JdbcRunEventStore` 已将 run event 的 durable replay、sequence 和 retention 放在
PostgreSQL 中，但当前 live subscriber 只存在单个 JVM 的内存中。两台 server 共享
PostgreSQL 时，事件写入实例不会把 live SSE 事件推送给连接位于另一实例的客户端。
Phase 6 的多实例 live fan-out 演练因此仍未完成。

此问题不改变 PostgreSQL 作为事件真相，也不改变 `space_id`、Last-Event-ID replay、
cancel 或 retention 语义。广播只用于低延迟唤醒，断线重连仍必须通过 durable replay
补齐事件。

## Decision

**Accepted（2026-08-22，用户明确接受方案）：** 复用现有 Valkey/Redis 协议，
为每个 server 实例建立受控 Pub/Sub listener。事件提交事务成功后发布最小 envelope：
`event_id`、`run_id`、`space_id`、`sequence` 和 schema version；接收实例只在拥有
对应 `space_id/run_id` 的本地 subscriber 时按 `event_id` 从 PostgreSQL 读取事件，
然后沿用现有的本地顺序投递逻辑。

必须满足以下边界：

1. Pub/Sub 丢失、重复、乱序或 Valkey 不可用时，不能丢失 durable event；SSE 断线或
   replay 必须从 PostgreSQL 恢复，广播不承担可靠队列职责。
2. envelope 不包含 prompt、answer、document、provider body、凭据或其他原文；
   `space_id` 是路由和读取查询的强制隔离条件。
3. 发布必须在 PostgreSQL 事务提交后发生，或由可重试的提交后机制触发，不能在回滚
   事务中泄露未提交事件。
4. 远端事件进入本地 subscriber 前要按 sequence 去重/补洞；发现缺口时从 PostgreSQL
   replay，而不是信任 Pub/Sub 的到达顺序。
5. listener 生命周期、断连重连、订阅关闭、异常计数和 backlog 必须可观测；Valkey
   仅用于 live hint，不得改变 Session、cache 和生产出境策略。
6. 双实例 Testcontainers/Compose 演练必须证明：跨实例投递、同空间隔离、跨空间零
   泄漏、重复/乱序恢复、Valkey 短暂不可用后的 durable replay，以及两实例停止后的
   资源清理。

## Consequences

- 增加一个共享基础设施 listener 和发布路径，但不引入第二套业务 backend。
- Valkey 故障不会使已提交事件丢失，但在线连接可能等待重连或依赖 Last-Event-ID
  replay；这需要告警和 runbook。
- 需要新增配置开关/频道命名、指标和集成演练，并重新跑受影响的 security、contract
  和 integration gates。
- 在本 ADR 被接受前，Phase 6 只能记录“实现选择待决”，不能声称多实例门槛已满足。

## Alternatives

- **PostgreSQL polling**：不增加 Valkey 语义，但会引入 polling 延迟和数据库压力；若
  选择该方案，需要单独定义轮询水位、退避和容量预算。
- **RabbitMQ fan-out**：可靠投递能力更强，但当前 RabbitMQ 用于异步任务/Outbox，
  将 SSE live hint 混入该链路会扩大职责和故障面。
- **继续单实例**：实现最小，但不满足 Phase 6 多实例 live fan-out 退出门槛。

## References

- [`ADR-0003`](0003-postgresql-qdrant-and-messaging.md)
- [`API_AND_EVENTS.md`](../API_AND_EVENTS.md)
- [`PHASE_6_CHECKLIST.md`](../../03-delivery/PHASE_6_CHECKLIST.md)
- [`JdbcRunEventStore`](../../../apps/server/src/main/java/com/ragforge/server/run/JdbcRunEventStore.java)
