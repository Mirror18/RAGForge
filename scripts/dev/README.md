# Development Scripts

统一入口是 `scripts/dev/core.py`，使用 Python 标准库和 Docker Compose CLI，Windows、WSL/Linux 和 CI 使用同一组命令：

```text
python scripts/dev/core.py config
python scripts/dev/core.py up
python scripts/dev/core.py health
# 需要本地模型时，额外检查 Ollama
python scripts/dev/core.py health --check-ollama
python scripts/dev/core.py ps
python scripts/dev/core.py backup-smoke --dry-run
python scripts/dev/core.py down
python scripts/dev/core.py --profile app build
python scripts/dev/core.py --profile app up --build
```

Windows 本地开发可使用 `start-local.bat` 一次性启动 core、Server、Worker 与 Web。`.bat` 是默认入口，内部调用 `start-local.ps1`；脚本要求 Docker Desktop、Java 21、Maven 和 Node.js，不要求启动 Ollama，并将启动日志写入已忽略的 `tmp/local-run/`：

```bat
.\scripts\dev\start-local.bat
# 仅启动 core 和 Server
.\scripts\dev\start-local.bat -SkipWeb
# 启动完成后打开浏览器
.\scripts\dev\start-local.bat -OpenBrowser
# 如需真实本地模型验收，显式检查 Ollama 和所需模型
.\scripts\dev\start-local.bat -CheckOllama
```

默认本地项目名为 `ragforge-p1`，Server/Web 端口为 `25082` 和 `25174`；可通过 `-ProjectName`、`-ServerPort`、`-WebPort` 调整。脚本默认不会访问 Ollama；传入 `-CheckOllama` 时才会检查 `qwen3.5:9b` 与 `nomic-embed-text:latest`。无论是否检查，脚本都不会把本地路由静默切换为云路由。

脚本启动 Server 和 Worker 时会先在前台清理并完成 Maven `clean compile`、`jar:jar` 与 `spring-boot:repackage`，固定使用 Java 21，并启用 `ragforge-isolated-output` profile，把 Maven 产物写入各模块的 `target-ragforge/`；随后直接运行构建出的、运行期间不会被 IDE 增量编译覆盖的 JAR。应用运行阶段不触发测试编译，IDE 增量编译也不会影响运行产物，避免机器级 Maven profile、切换分支或源码移动造成 `target/classes` 不完整，进而出现 `NoClassDefFoundError`。测试编译仍由独立回归命令执行；完整根工程回归命令使用 `mvn -Pragforge-isolated-output clean test`。脚本会等待 Worker 的 Spring Boot 启动日志，并把应用 JVM PID 写入 `tmp/local-run/worker.pid`（不是 Maven 包装进程）；Worker 编译或启动失败时会返回非零并打印最近日志，不会继续报告“已就绪”。

启动前会检查当前项目生成的 Server/Worker JAR 是否已有 Java 进程运行；发现旧实例时会直接失败并报告 PID，需先停止旧实例，避免重复消费和构建产物竞态。

完整的当前应用运行面需要下列 Docker core 服务：

| 服务 | 用途 | 默认宿主机端口 |
| --- | --- | --- |
| PostgreSQL | 业务数据、版本与审计真相 | `25432` |
| Qdrant | dense candidate index | `26333`、`26334` |
| RabbitMQ | outbox 与摄取事件传输 | `25672`、管理台 `25673` |
| Valkey | Session、缓存和 run-event fanout | `26379` |
| MinIO | 原始文件与解析产物 | `29000`、控制台 `29001` |

Server 和 Web 默认仍从宿主机源码启动；需要完整容器化运行时可启用 `app` profile。应用镜像统一由 `deploy/docker/Dockerfile` 的 `server`、`worker`、`web` targets 生成。Ollama 是可选的宿主机服务（`11434`），其 Compose `ollama` profile 仅用于明确选择的全容器化 smoke 环境；只有使用本地模型时才需要启动它。`observability.yaml` 中的 OTel Collector、Prometheus、Grafana、Loki 和 Tempo 是可选运维观测面，不是应用功能依赖。

来源库与任务中心使用空间内服务端 cursor 分页，页面默认每页 10 条；搜索词通过 `q` 参数传给接口，不会先把全部数据加载到浏览器再过滤。

`backend/ingestion-worker` 已有 `BusinessIngestionSideEffectHandler` 实现；宿主机启动脚本和 Compose `app` profile 都会以 `RAGFORGE_INGESTION_ENABLED=true` 启动可消费任务的 Worker。宿主机脚本与容器化 profile 只能选择一种运行模式，避免重复消费或端口占用。

启动后可直接打开 Web 完成真实业务闭环：注册/登录 → 创建空间 → 按需配置本地 Ollama 或显式选择其他已授权模型路由 → 选择 Markdown（或显式选择本地 notes 文件夹）→ 等待摄取和 active index → 带引用问答 → Run/Step/usage → 再次上传修改后的同一文件验证增量 Revision。可复核证据见 [`tests/evidence/business-loop-e2e.v1.json`](../../tests/evidence/business-loop-e2e.v1.json)。个人 notes 不会被服务端自动扫描，云端 route 也不会自动回退。

`--project-name` 是本地隔离边界。日常开发只使用默认项目 `ragforge-p1`；入口会强制派生
`<project-name>-core` network、`<project-name>_...` volumes，以及稳定的 host-port
block。基准 project `ragforge-p1` 保留原始端口；其他 project 使用
`SHA-256(project_name) mod 997 * 20` 作为偏移，所有端口限制在 `[20000, 50000)`，不使用
随机数或机器状态。`--env-file` 中的固定占位端口不会覆盖统一入口的派生规则。

例如，使用默认项目名启动时：

```text
.\scripts\dev\start-local.bat
# Web: http://127.0.0.1:25174
# Server: http://127.0.0.1:25082
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
