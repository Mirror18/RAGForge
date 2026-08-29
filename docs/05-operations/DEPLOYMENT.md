# 部署设计

## 1. 首发拓扑

Linux Docker Compose 是 MVP 正式交付形态，Ubuntu 24.04 WSL2 是本机验收环境。规范化部署入口位于 `deploy/`，应用镜像统一由 `deploy/docker/Dockerfile` 构建。部署拆为五个 profile/运行面：

- `core`：`deploy/compose/compose.yaml` 默认启用的 PostgreSQL、Qdrant、RabbitMQ、Valkey、MinIO 基础设施。
- `app`：同一 Compose 文件中显式启用的 Server、ingestion Worker 和静态 Web 容器。
- `ollama`：同一 Compose 文件中的可选 Ollama 容器；默认仍连接宿主机 Ollama，不静默切换。
- `observability`：OpenTelemetry Collector、Prometheus、Grafana、Loki、Tempo。
- `llmops`：可选 Langfuse 及其依赖；资源较重，不是日常开发必启项。

目录与入口索引：

| 路径 | 用途 |
|---|---|
| `deploy/compose/compose.yaml` | core/app/ollama Compose 服务定义 |
| `deploy/compose/observability.yaml` | observability overlay |
| `deploy/docker/Dockerfile` | server/worker/web 统一多目标构建 |
| `deploy/docker/nginx.conf` | Web 容器代理与 SPA fallback |
| `scripts/dev/core.py` | Compose 构建、启动、停止和健康入口 |
| `scripts/dev/start-local.bat` | Windows 宿主机源码启动入口 |

容器化开发命令：

```text
python scripts/dev/core.py --profile app build
python scripts/dev/core.py --profile app up --build
python scripts/dev/core.py --profile app ps
python scripts/dev/core.py --profile app down
```

Windows 本地源码模式命令：

```bat
.\scripts\dev\start-local.bat
```

两种模式共享 core 数据服务但不应同时启动同一组 Server/Worker/Web；重复启动会触发端口占用或重复消费风险。

Ollama 默认运行在宿主机，通过明确地址连接；生产可替换为局域网推理服务。Compose 只保存非敏感默认值，Secret 由环境或 secret files 注入。

## 2. 环境

| 环境 | 用途 | 数据 |
|---|---|---|
| Local | 开发和单用户模型体验 | 合成 + 本地只读 Obsidian |
| CI | 可重复自动验证 | 固定合成 fixtures |
| Staging | 拓扑、升级、安全、性能验收 | 合成/脱敏 |
| Production | 企业内部使用 | 受治理真实数据 |

## 3. 容器要求

- 非 root 用户、只读 root filesystem（可行时）、最小 capabilities。
- 镜像使用 immutable tag/digest，生成 SBOM，扫描 OS/依赖漏洞。
- 明确 liveness/readiness/startup，不能只检查端口。
- CPU/内存/PID/文件描述符限额和临时盘预算。
- SIGTERM 后停止接新任务，完成/安全中断当前事务并在时限内退出。
- 日志输出 stdout/stderr 结构化 JSON，不把 Secret 和完整敏感内容写日志。

## 4. 配置与 Secret

配置优先级：安全代码默认值 < versioned config < environment override < secret reference。模型凭据、数据库密码、S3 key、Session signing/encryption keys 不进入 Git、镜像、Compose 展开日志或错误响应。

### 4.1 首个平台管理员初始化

平台管理员 bootstrap 默认关闭，不自动提升首个注册用户。仅在干净环境首次设置期间，通过受控 Secret 注入至少 32 字符的 `RAGFORGE_BOOTSTRAP_ADMIN_TOKEN`；不要把值写入 `.env`、Compose 文件、命令历史、日志或工单。

1. 限制 Server/Web 入口只允许负责初始化的操作者访问，并核对目标管理员邮箱。
2. 注入 Token 后启动应用；登录页在“尚无 ACTIVE 平台管理员”时显示首次设置表单。
3. 提交后确认返回 `PLATFORM_ADMIN`，使用新密码登录，并检查 `platform.admin.bootstrapped.v1` 审计事件只含 `userId` 和 `mode`。
4. 立即从 Secret/运行环境移除 Token，并重启或滚动应用使其失效；再次查询首次设置状态应为 `required=false`、`available=false`。

并发请求由数据库事务锁保证最多一次成功；管理员已存在时接口固定返回冲突。若怀疑 Token 泄露，在初始化前立刻轮换；若出现非预期成功事件，隔离入口、禁用异常账户并按凭据轮换和事件响应流程处理。该流程不授权生产迁移或发布。

## 5. 发布流程

1. 固定 commit、镜像 digest、迁移版本和 SBOM。
2. 备份并验证最近恢复点。
3. 在 staging 执行迁移、部署、smoke、E2E 和安全探针。
4. 生产先部署兼容 schema，再滚动应用，最后异步回填。
5. 验证登录、空间授权、导入、检索、问答、SSE、指标和告警。
6. 观察错误/延迟/队列/资源后关闭变更窗口。

## 6. 回滚原则

- 应用回滚只回到仍兼容当前数据库 schema 的版本。
- 数据库优先 forward fix；破坏性 migration 禁止与单次发布耦合。
- active index pointer 可快速切回上一 READY/RETIRED 版本。
- Prompt、retrieval 和 route binding 可切回上一个已评估版本。
- 回滚动作和原因进入审计与 incident timeline。

## 7. Git 数据源

开发环境只读挂载 `D:\project\learning\notes`；Linux 生产通过 Git connector 拉取 Gitee 仓库，使用只读 credential、固定 branch 和明确 include/exclude。RAGForge 不向 Obsidian 仓库自动写项目进度；阶段复盘需人工确认后再摘录。

## 8. Kubernetes

`deploy/kubernetes/` 仅预留。没有证明 Compose 无法满足可靠性、组织或容量需求前，不维护第二套生产部署面。
