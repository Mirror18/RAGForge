# RAGForge

RAGForge 是面向企业内部、多用户知识空间的商业级 RAG 学习工程，覆盖数据接入、解析、分块、索引、检索、生成、结构化引用、安全、评估、部署和复盘。

## 先看这里

不知道下一步做什么时，打开 [`docs/README.md`](./docs/README.md)，然后按顺序阅读 01–07 主文档。要执行任务时只走：

1. [项目状态卡](./docs/08-项目状态卡.md)
2. [项目任务总台账](./docs/09-项目任务总台账.md)
3. 对应 [Worker Ticket](./.agents/tickets/)
4. 独立 worktree、验证、中文 Conventional Commit

当前主线和阻塞以状态卡为准；任务目标与达到的效果以任务板为准；实际改动、测试、风险和回滚以 Git commit body 为准。

## 代码与启动

```text
frontend/ragforge-web/    # Vue SPA
backend/server/            # 模块化单体 API 和业务编排
backend/ingestion-worker/  # 独立异步摄取 Worker
backend/ai-runtime/        # OCR/rerank 窄职责运行时
contracts/ tests/ fixtures/ scripts/ # 跨应用共享边界
config/ deploy/ libs/      # 配置、运行资产和受约束复用库
```

前置条件：Docker Desktop、Java 21、Maven、Node.js、Python；真实本地模型验收另需 Ollama。

```powershell
.\scripts\dev\start-local.bat
```

直接运行资产：[`deploy/docker/Dockerfile`](./deploy/docker/Dockerfile) · [`deploy/compose/compose.yaml`](./deploy/compose/compose.yaml) · [`scripts/dev/start-local.bat`](./scripts/dev/start-local.bat)。

详细启动和目录职责见[工程结构与本地运行](./docs/03-工程结构与本地运行.md)，前端见 [`frontend/README.md`](./frontend/README.md)，后端见 [`backend/README.md`](./backend/README.md)，部署见 [`deploy/README.md`](./deploy/README.md)。

## 根目录保留的机器/平台文件

`AGENTS.md` 是仓库硬规则，必须留在根目录；`README.md` 是项目入口；`CHANGELOG.md` 是发布历史，按 Keep a Changelog 保留在根目录。GitHub 的贡献和安全入口放在 `.github/`；许可证、第三方说明和 Agent 经验不再在根目录复制，分别维护在 [`docs/06-安全合规与研究.md`](./docs/06-安全合规与研究.md) 和 [`docs/13-Agent工程记忆.md`](./docs/13-Agent工程记忆.md)。

不要提交凭据、个人 Obsidian 内容、生产数据、raw prompt、构建产物或未经批准的第三方源码；本机敏感配置放在被 `.gitignore` 保护的 [`config/private/`](./config/private/)。
