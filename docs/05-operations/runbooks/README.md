# Runbooks

实施阶段每个高优先级告警都必须链接一个可执行 Runbook。建议文件：

- `provider-outage.md`
- `ingestion-backlog.md`
- `dead-letter-replay.md`
- `index-publish-failure.md`
- `qdrant-rebuild.md`
- `database-capacity.md`
- `unauthorized-egress.md`
- `suspected-space-leak.md`
- `backup-restore.md`

每个 Runbook 固定结构：

1. 症状与用户影响。
2. 安全边界和禁止动作。
3. Dashboard、查询和只读诊断。
4. 缓解步骤。
5. 恢复与验证。
6. 回滚。
7. 升级联系人/SLA。
8. 证据和复盘记录位置。

占位清单不是已完成 Runbook。对应能力进入可部署范围时必须补齐命令、预期输出和演练证据。
