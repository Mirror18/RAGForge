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
