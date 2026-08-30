# Development Scripts

统一入口是 `scripts/dev/core.py`，使用 Python 标准库和 Docker Compose CLI，Windows、WSL/Linux 和 CI 使用同一组命令：

```text
python scripts/dev/core.py config
python scripts/dev/core.py up
python scripts/dev/core.py health
python scripts/dev/core.py ps
python scripts/dev/core.py backup-smoke --dry-run
python scripts/dev/core.py down
python scripts/dev/core.py --profile app build
python scripts/dev/core.py --profile app up --build
```

Windows 本地开发可使用 `start-local.bat` 一次性启动 core、Server、Worker 与 Web。`.bat` 是默认入口，内部调用 `start-local.ps1`；脚本要求 Docker Desktop、Java 21、Maven、Node.js 和本机 Ollama，并将启动日志写入已忽略的 `tmp/local-run/`：

```bat
.\scripts\dev\start-local.bat
# 仅启动 core 和 Server
.\scripts\dev\start-local.bat -SkipWeb
# 启动完成后打开浏览器
.\scripts\dev\start-local.bat -OpenBrowser
```

默认本地项目名为 `ragforge-p1-local`，Server/Web 端口为 `18084` 和 `5176`，用于避开已运行的 `ragforge-p1` 项目；可通过 `-ProjectName`、`-ServerPort`、`-WebPort` 调整。脚本会检查 `qwen3.5:9b` 与 `nomic-embed-text:latest`，并为 Server 显式启用 MinIO、Qdrant、RabbitMQ outbox relay、Valkey run-event fanout 和 Phase 6 运维任务；不会把本地路由静默切换为云路由。

完整的当前应用运行面需要下列 Docker core 服务：

| 服务 | 用途 | 默认宿主机端口 |
| --- | --- | --- |
| PostgreSQL | 业务数据、版本与审计真相 | `43052` |
| Qdrant | dense candidate index | `43953`、`43954` |
| RabbitMQ | outbox 与摄取事件传输 | `43292`、管理台 `43293` |
| Valkey | Session、缓存和 run-event fanout | `43999` |
| MinIO | 原始文件与解析产物 | `46620`、控制台 `46621` |

Server 和 Web 默认仍从宿主机源码启动；需要完整容器化运行时可启用 `app` profile。应用镜像统一由 `deploy/docker/Dockerfile` 的 `server`、`worker`、`web` targets 生成。Ollama 默认也是宿主机服务（`11434`），其 Compose `ollama` profile 仅用于明确选择的全容器化 smoke 环境。`observability.yaml` 中的 OTel Collector、Prometheus、Grafana、Loki 和 Tempo 是可选运维观测面，不是应用功能依赖。

`apps/ingestion-worker` 已有 `BusinessIngestionSideEffectHandler` 实现；宿主机启动脚本和 Compose `app` profile 都会以 `RAGFORGE_INGESTION_ENABLED=true` 启动可消费任务的 Worker。宿主机脚本与容器化 profile 只能选择一种运行模式，避免重复消费或端口占用。

启动后可直接打开 Web 完成真实业务闭环：注册/登录 → 创建空间 → 初始化本地 Ollama → 选择 Markdown（或显式选择本地 notes 文件夹）→ 等待摄取和 active index → 带引用问答 → Run/Step/usage → 再次上传修改后的同一文件验证增量 Revision。可复核证据见 [`tests/evidence/business-loop-e2e.v1.json`](../../tests/evidence/business-loop-e2e.v1.json)。个人 notes 不会被服务端自动扫描，云端 route 也不会自动回退。

`--project-name` 是本地隔离边界。默认项目 `ragforge-p1-local` 会强制派生独立的
`ragforge-p1-local-core` network、独立 volume 和以下稳定端口 block；入口会强制派生
`<project-name>-core` network、`<project-name>_...` volumes，以及稳定的 host-port
block。基准 project `ragforge-p1` 保留原始端口；其他 project 使用
`SHA-256(project_name) mod 997 * 20` 作为偏移，所有端口限制在 `[20000, 50000)`，不使用
随机数或机器状态。`--env-file` 中的固定占位端口不会覆盖统一入口的派生规则。

例如，使用默认项目名启动时：

```text
.\scripts\dev\start-local.bat
# Web: http://127.0.0.1:5176
# Server: http://127.0.0.1:18084
```

也可显式指定项目名和应用端口：

```text
python scripts/dev/core.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example config
# offset=3980；PostgreSQL=29412，Qdrant=30313，RabbitMQ=29652，Valkey=30359，S3=32980，Ollama profile=25414
```

可执行验证会同时检查 service 清单、network、volume 和 host-port block：

```text
python scripts/ci/validate_compose.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example
```

命令会在 Docker 不可用、Compose 配置缺失、端口映射越界或子命令失败时返回非零退出码，并保留 Docker/脚本错误上下文。
