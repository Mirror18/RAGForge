# 备份与恢复方案

## 1. 目标

- 设计目标：RPO 24 小时，RTO 4 小时。
- 目标必须通过隔离恢复演练验证，备份任务成功不等于可恢复。

## 2. 数据分级

| 组件 | 角色 | 备份策略 |
|---|---|---|
| PostgreSQL | 业务真相、版本、审计 | 每日逻辑/物理备份；生产化后评估 PITR/WAL |
| Object Storage | 原文和 artifacts | 版本化/快照 + manifest/hash |
| Qdrant | 可重建索引 | 快照加速恢复，同时保留从真相重建能力 |
| Valkey | Session/缓存 | 不作为关键真相；恢复后用户可重新登录 |
| RabbitMQ | 传输 | 关键状态在 DB；队列持久化但不替代业务备份 |
| Config/Secrets | 运行能力 | Git 中非敏感配置 + 独立 Secret 备份/轮换方案 |

## 3. 一致性

恢复点必须记录 PostgreSQL backup ID、object snapshot/manifest、Qdrant snapshot/index versions 和应用 schema version。若无法取得完全一致快照，以 PostgreSQL 为真相：恢复对象后验证 hash，Qdrant 对 active index 做校验或重建。

## 4. 恢复顺序

1. 在隔离网络准备干净基础设施和对应镜像。
2. 恢复 PostgreSQL，验证 migration/schema 和关键计数。
3. 恢复对象并按 manifest/hash 抽验。
4. 恢复或重建 Qdrant，仅发布验证通过的 index version。
5. 启动 Valkey/RabbitMQ 和应用，暂停自动 source sync。
6. 运行空间隔离、引用、抽样检索、登录和审计冒烟。
7. 比较 RPO，确认无重复 Outbox/job，再逐步恢复摄取。

## 5. 演练

至少每季度执行：完整恢复、PostgreSQL 单点恢复、Qdrant 丢失重建、对象缺失检测和 active index 回滚。记录开始/完成、实际 RPO/RTO、数据差异、人工步骤和改进 owner。

## 6. 禁止事项

- 不在同一磁盘只保留一份备份。
- 不把包含真实 Secret 的备份上传公共 artifact。
- 不未经验证就把恢复环境接回生产数据源。
- 不把缓存或消息队列当作唯一恢复来源。
