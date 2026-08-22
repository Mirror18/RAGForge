# Phase 6 保留、审计与成本运维能力

## 范围

`Phase6OperationsService` 提供三项运维能力：

- 通过定时任务清理超过保留期限的答案聚合及 durable SSE 事件；事件流游标保留，避免清理后重用序列号。
- 按 `space_id` 和时间窗口导出审计索引。导出只包含事件元数据及 payload SHA-256，不导出审计 payload、原始问题、答案或凭据。
- 按 `space_id` 和时间窗口聚合 usage ledger 的 token、条目数和估算成本。

## 启用与验证

默认关闭，启用需要显式设置 `RAGFORGE_PHASE6_OPERATIONS_ENABLED=true`。清理间隔由
`RAGFORGE_PHASE6_CLEANUP_DELAY_MS` 控制，默认一小时。数据库迁移和清理只能在隔离环境验证；本能力不授权生产迁移。

单元验证：

```text
mvn -pl apps/server -Dtest=Phase6OperationsServiceTest test
```

所有 SQL 查询都必须携带空间和时间窗口参数。审计导出中的 hash 用于外部归档后完整性核验，不能反推出 payload 内容。
