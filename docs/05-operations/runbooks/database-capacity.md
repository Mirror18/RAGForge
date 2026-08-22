# 数据库容量、连接池或恢复点异常

1. 症状与用户影响

`RAGForgeDatabaseCapacityHigh` 或 `RAGForgeRecoveryPointStale` 触发；API 可能排队
或失败，故障时 RPO 可能超过 24 小时。

2. 安全边界和禁止动作

只读查看连接池、磁盘、备份状态和指标。不得在生产删除表/卷、执行未批准 migration、
复制数据库内容或在没有恢复点验证时清理备份。

3. Dashboard、查询和只读诊断

```promql
max(ragforge_db_connections_used / clamp_min(ragforge_db_connections_max, 1))
time() - ragforge_backup_last_success_timestamp_seconds
ragforge_object_storage_bytes
ragforge_qdrant_storage_bytes
```

结合 Grafana Tempo 的 DB span 耗时和安全日志中的 `error_code`；只使用连接池
统计、表/卷大小和 opaque backup id，不导出 SQL/正文。

4. 缓解步骤

- 判断是连接泄漏、慢查询、磁盘耗尽、备份失败还是对象/Qdrant 增长。
- 限制非必要摄取/重试，保留在线查询能力；不得通过无限增大连接池掩盖泄漏。
- 按 `BACKUP_RESTORE.md` 检查最近成功恢复点和备份 hash；任何清理动作需人工批准。

5. 恢复与验证

确认连接池、磁盘和恢复点年龄回到阈值内；在隔离环境验证 PostgreSQL、对象、
Qdrant、active index、引用和 outbox 计数。记录实际 RPO/RTO，不能用备份命令成功
代替恢复验证。

6. 回滚

回滚最近应用/配置变更到 schema 兼容版本；active index 可切回上一 READY 版本。
生产 migration 和 volume 操作必须走审批流程。

7. 升级联系人/SLA

P1 容量超过 80% 或恢复点超过 24 小时，5 分钟内通知平台、DB、存储 on-call；
15 分钟无缓解升级 incident commander。

8. 证据和复盘记录位置

记录指标时间窗、backup id、object/Qdrant snapshot version、schema version、实际
RPO/RTO 和人工步骤；证据放在 `tests/evidence/phase6-observability-*.json`。
