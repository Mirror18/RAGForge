# Development Scripts

统一入口是 `scripts/dev/core.py`，使用 Python 标准库和 Docker Compose CLI，Windows、WSL/Linux 和 CI 使用同一组命令：

```text
python scripts/dev/core.py config
python scripts/dev/core.py up
python scripts/dev/core.py health
python scripts/dev/core.py ps
python scripts/dev/core.py backup-smoke --dry-run
python scripts/dev/core.py down
```

Windows 本地开发可使用 `start-local.bat` 一次性启动 core、Server 与 Web。`.bat` 是默认入口，内部调用 `start-local.ps1`；脚本要求 Java 21，并将启动日志写入已忽略的 `tmp/local-run/`：

```bat
.\scripts\dev\start-local.bat
# 仅启动 core 和 Server
.\scripts\dev\start-local.bat -SkipWeb
```

默认 Server/Web 端口为 `18082` 和 `5174`，可通过 `-ServerPort`、`-WebPort` 调整；`-ProjectName` 保持 Compose 网络、卷和基础设施端口隔离。

`--project-name` 是本地隔离边界。入口会强制派生 `<project-name>-core` network、
`<project-name>_...` volumes，以及稳定的 host-port block：默认 `ragforge-p1` 保留
基准端口；其他 project 使用 `SHA-256(project_name) mod 997 * 20` 作为偏移，所有端口
限制在 `[20000, 50000)`，不使用随机数或机器状态。`--env-file` 中的固定占位端口不会
覆盖统一入口的派生规则。

例如：

```text
python scripts/dev/core.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example config
# offset=3980；PostgreSQL=29412，Qdrant=30313，RabbitMQ=29652，Valkey=30359，S3=32980，Ollama profile=25414
```

可执行验证会同时检查 service 清单、network、volume 和 host-port block：

```text
python scripts/ci/validate_compose.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example
```

命令会在 Docker 不可用、Compose 配置缺失、端口映射越界或子命令失败时返回非零退出码，并保留 Docker/脚本错误上下文。
