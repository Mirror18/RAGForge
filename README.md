# RAGForge

RAGForge 是面向企业内部、多用户知识空间的商业级 RAG 学习工程，覆盖数据接入、解析、分块、索引、检索、生成、结构化引用、安全、评估、部署和复盘。

## 先看这里

现行说明按代码写在 backlog：

1. [当前代码](<./backlog/docs/architecture/doc-1 - 当前代码.md>)
2. [运行与安全](<./backlog/docs/operations/doc-2 - 运行与安全.md>)
3. [specs](./specs/README.md) 与 [backlog 任务](./backlog/)

任务目标以任务板为准；实际改动、测试、风险和回滚以 Git commit body 为准。

## 代码与启动

```text
frontend/ragforge-web/     # Vue SPA，页面在 src/views
backend/server/            # 同步 API
backend/ingestion-worker/  # 异步摄取
backend/ai-runtime/        # Python rerank
specs/ backlog/            # 规格、任务板、按代码写的说明
tests/ scripts/
deploy/
```

前置条件：Docker Desktop、Java 21、Maven、Node.js、Python。真实生成需要已授权的云端 API 凭据。

```powershell
.\scripts\dev\start-local.bat
```

直接运行资产：[`deploy/docker/Dockerfile`](./deploy/docker/Dockerfile) · [`deploy/compose/compose.yaml`](./deploy/compose/compose.yaml) · [`scripts/dev/start-local.bat`](./scripts/dev/start-local.bat)。

目录和启动见[当前代码](<./backlog/docs/architecture/doc-1 - 当前代码.md>)，前端见 [`frontend/README.md`](./frontend/README.md)，后端见 [`backend/README.md`](./backend/README.md)，部署见 [`deploy/README.md`](./deploy/README.md)。

## 根目录保留的机器/平台文件

`AGENTS.md` 是仓库硬规则，必须留在根目录；`README.md` 是项目入口；`CHANGELOG.md` 是发布历史。GitHub 的贡献和安全入口放在 `.github/`。运行、告警和许可证说明在[运行与安全](<./backlog/docs/operations/doc-2 - 运行与安全.md>)。

不要提交凭据、个人 Obsidian 内容、生产数据、raw prompt、构建产物或未经批准的第三方源码。本机凭据放在被 `.gitignore` 保护的 `.env.local`。
