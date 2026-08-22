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

## 7. Phase 6 隔离恢复演练

仓库提供一个只使用合成 fixture 的可重复演练 harness：
[`scripts/phase6/recovery_verification.py`](../../scripts/phase6/recovery_verification.py)。它复用仓库 Compose 基础设施，但强制使用 `ragforge-p6-recovery-<suffix>` project、独立端口和独立 volume；生产、`main`、`staging` 等 project 名称会被拒绝。演练不读取应用生产配置，不接受非本机 PostgreSQL/Qdrant/Object endpoint，也不输出凭据或原文到证据。

fixture 固定在 [`tests/fixtures/phase6/recovery/recovery-fixture.v1.json`](../../tests/fixtures/phase6/recovery/recovery-fixture.v1.json)，内容只包含 RAGForge 自有的公开合成材料。执行命令：

```powershell
python scripts/phase6/recovery_verification.py `
  --project-name ragforge-p6-recovery-local `
  --output tests/evidence/phase6-recovery.v1.json
```

演练实际执行以下恢复链路，而不是只执行 `backup_smoke.py`：

1. 启动隔离 PostgreSQL、MinIO 和 Qdrant，执行仓库 V1–V13 migration，并把合成 fixture 写入真实 schema。
2. 创建 PostgreSQL `pg_dump`，恢复到 `recovery_full` 和 `recovery_pg_only` 两个独立数据库，比较 schema version、migration hash、关键表计数和 material hash。
3. 在 MinIO 创建对象 manifest，保存对象 SHA-256；先删除对象验证 404 缺失检测，再恢复并重新校验 hash。
4. 在 Qdrant 创建三维测试集合和 snapshot，模拟 collection 丢失，从恢复后的 PostgreSQL `child_chunks` 重建，校验向量维度、active index 版本和空间 payload。
5. 将 active index pointer 从当前版本回滚到 previous 版本，重放 synthetic delete ledger；重复重放必须保持计数不变。
6. 使用数据库唯一约束和 `ON CONFLICT DO NOTHING` 重放 Outbox/job，验证行数、事件 ID 和 ingestion idempotency key 均不重复。

最新一次真实隔离演练的完整证据在 [`tests/evidence/phase6-recovery.v1.json`](../../tests/evidence/phase6-recovery.v1.json)，包括 backup ID、PostgreSQL schema/migration manifest、对象 manifest/hash、Qdrant snapshot/rebuild/index/counts、RPO/RTO、差异、人工步骤和 owner。证据中的 `production_connection=false`、Compose project、fixture SHA-256 与安全范围字段必须随每次演练保留。

失败路径包括 PostgreSQL readiness polling、migration/fixture 约束失败、Qdrant 未授权或不可用、对象缺失 404、对象 hash 不匹配、schema/关键计数不一致、active index 未回滚、tombstone 重放非幂等以及 Outbox/job 重复。失败后 harness 会写入部分证据，并在默认模式清理仅由该唯一 project 创建的隔离 volume；调试时可使用 `--keep-stack`，但仍不得连接生产。

测试命令：

```powershell
python -m unittest scripts/phase6/test_recovery_verification.py -v
```

值班 owner：`platform-oncall`；恢复改进 owner：`platform-data`。恢复窗口仍须由值班人员在执行前确认并冻结写入；本 harness 的 `RPO=0s` 是冻结的合成演练结果，不外推为生产 PITR 保证。
