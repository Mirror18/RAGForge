# 部署设计

## 1. 首发拓扑

Linux Docker Compose 是 MVP 正式交付形态，Ubuntu 24.04 WSL2 是本机验收环境。部署拆为三个 profile：

- `core`：proxy、web、server、worker、ai-runtime、PostgreSQL、Qdrant、RabbitMQ、Valkey、S3-compatible storage。
- `observability`：OpenTelemetry Collector、Prometheus、Grafana、Loki、Tempo。
- `llmops`：可选 Langfuse 及其依赖；资源较重，不是日常开发必启项。

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

