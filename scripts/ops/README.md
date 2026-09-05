# Operations Scripts

当前阶段提供两个可执行运维探针：

- `python scripts/ops/health_probe.py` 默认检查 PostgreSQL、Qdrant、RabbitMQ、Valkey 和
  MinIO；任一必需服务不可达都会返回 1，并列出具体目标和错误。需要验证本地模型服务时，
  显式追加 `--check-ollama`，此时 Ollama 也会成为失败条件。
- `python scripts/ops/backup_smoke.py --dry-run` 检查备份命令计划；不带 `--dry-run`
  时通过 Compose 容器内 `pg_dump` 写入 `tmp/backups/`。输出目录已被 gitignore，
  脚本不会上传备份或打印凭据。

业务索引重建、DLQ 重放、删除验证等脚本必须等相应 contract 和服务实现后再加入，
不能在本阶段用不存在的业务命令伪造运维能力。
