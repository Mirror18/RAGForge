# Docker Compose Core

`compose.yaml` 提供 Phase 1 基础设施骨架：PostgreSQL、Qdrant、RabbitMQ、Valkey、MinIO
（S3-compatible storage），以及可选的容器化 Ollama profile。默认 Ollama 连接宿主机
`http://host.docker.internal:11434`，不会静默切换到云端 provider。

## 使用

推荐使用统一入口：

```text
python scripts/dev/core.py config
python scripts/dev/core.py up
python scripts/dev/core.py health
python scripts/dev/core.py ps
python scripts/dev/core.py down
```

`--project-name` 会同步派生独立资源和稳定端口 block：默认 `ragforge-p1` 保留基准端口；
其他 project 使用 SHA-256 派生的固定偏移（20 的倍数），并限制在 `[20000, 50000)`。
比如 `ragforge-p1-orch-check` 的 offset 是 `3980`，会得到：

```text
network=ragforge-p1-orch-check-core
volume=ragforge-p1-orch-check_postgres-data
postgres=29412, qdrant=30313, rabbitmq=29652, valkey=30359, s3=32980, ollama=25414
```

它不会使用固定的 `ragforge-p1-core`、`ragforge-p1_postgres-data` 或固定 host ports。
可执行验收为：

```text
python scripts/ci/validate_compose.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example
```

默认宿主机端口保持避开常见 `5432`、`6333`、`5672`、`6379`、`9000` 的范围；端口 block、
network、volume 由统一入口一起派生，不使用随机分配。

`down` 默认保留卷；删除当前 project 的本地实验数据时必须显式使用：

```text
python scripts/dev/core.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example down --volumes
```

## 健康、备份与失败路径

- `scripts/ops/health_probe.py` 检查 PostgreSQL、Qdrant、RabbitMQ、Valkey、MinIO 和 Ollama；
  任一失败都会返回非零退出码。统一入口会把派生端口传给 probe，并支持启动窗口重试。
- `scripts/ops/backup_smoke.py` 通过容器内 `pg_dump` 写入 gitignore 目录 `tmp/backups/`；
  容器未运行、命令失败或输出异常过短都会失败，不上传备份也不打印密码。
- `scripts/ci/validate_compose.py` 调用 `docker compose config`，检查必需服务、Ollama profile、
  健康检查、network/volume/host-port 隔离和 `gs-*` 禁止引用。

`env.example` 只有开发占位值，不得作为共享环境或生产凭据使用。

## Observability profile（Phase 6）

`observability.yaml` 是与 core Compose 叠加使用的独立 profile，提供
OpenTelemetry Collector、Prometheus、Grafana、Loki 和 Tempo。它不修改 core
服务，也不启用 Langfuse；Langfuse 仍是可选的独立 `llmops` 方案。

启动前必须在进程环境中提供 Grafana 管理员密码；密码不写入仓库、Compose
文件或命令行参数：

```powershell
$env:GRAFANA_ADMIN_PASSWORD = "<从本机 Secret Store 读取>"
docker compose --project-name ragforge-p6-observability `
  --file deploy/compose/compose.yaml `
  --file deploy/compose/observability.yaml `
  --profile observability up -d
```

验证配置和服务清单：

```text
docker compose --project-name ragforge-p6-observability \
  --file deploy/compose/compose.yaml \
  --file deploy/compose/observability.yaml \
  --profile observability config --quiet
docker compose --project-name ragforge-p6-observability \
  --file deploy/compose/compose.yaml \
  --file deploy/compose/observability.yaml \
  --profile observability ps
```

默认入口为 Grafana `http://localhost:23000`、Prometheus
`http://localhost:29090`、Loki `http://localhost:23100`、Tempo
`http://localhost:23200`。端口和 volume 前缀都可通过环境变量隔离；不能把
这些端口直接暴露到不受控网络。

Collector 接收 OTLP gRPC `24317`、OTLP HTTP `24318`，并在 `28889` 暴露
Prometheus exporter。`observability/otel-collector.yaml` 在导出前删除
Authorization/Cookie、prompt/document body、数据库语句和 LLM 输入输出；仅
保留受控的 `trace_id`、`correlation_id`、`run_id`、`space_id` 及状态/耗时等
投影字段。Grafana 日志面板因此只能用于关联诊断，不能用于读取正文。

这套 profile 的 runtime 验收命令、指标契约和故障演练记录见
[`docs/05-operations/observability-profile.md`](../../docs/05-operations/observability-profile.md)
与 [`scripts/phase6/observability_drill.py`](../../scripts/phase6/observability_drill.py)。
