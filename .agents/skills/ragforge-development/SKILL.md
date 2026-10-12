---
name: ragforge-development
description: Implement, test, review, or reorganize RAGForge source, contracts, deployment, and project records. Use for work inside this repository; do not use for unrelated general questions.
---

# RAGForge Development

仓库级硬约束以根目录 `AGENTS.md` 为准。现行代码在 `backend/server`、`backend/ingestion-worker`、`backend/ai-runtime` 和 `frontend/ragforge-web`。ADR-0014 的四个进程还没有独立目录。任务源是 `specs/` 与 `backlog/`。

## 开始前

1. 日常任务先读 [`当前代码`](<../../../backlog/docs/architecture/doc-1 - 当前代码.md>)、[`任务与文档工作流`](<../../../backlog/docs/operations/doc-3 - 任务与文档工作流.md>) 和 `backlog/tasks`。历史阶段记录在 Git history。
2. 执行 `git status --short --branch`，保留用户已有改动；只实现 backlog 卡片，不把 `specs/*/tasks.md` 当执行队列。
3. speckit 生成或更新 `tasks.md` 后，运行 `python scripts/dev/import_speckit_tasks.py --feature-dir specs/<id>` 再开工。
4. 一张卡验收通过后立即用中文 Conventional Commit 提交，再开始下一张。
5. Worker 只读 Ticket 的 allow-list，不通过全仓搜索补上下文。

## 按领域路由

- `backend/server/`：同步 API。身份、空间、云端模型、摄取入口、分块、索引、检索、回答和 run 都在这里的 Java 包。
- `backend/ingestion-worker/`：异步摄取。
- `backend/ai-runtime/`：Python rerank。
- `frontend/ragforge-web/`：页面在 `src/views/{access,space,knowledge,answer,admin}`。
- `backend/contracts/`：OpenAPI 和 JSON schema，描述 HTTP 与事件的字段。不是独立进程。
- `specs/`、`backlog/`：规格、任务板，以及 `backlog/docs/` 里按代码写的说明。
- `deploy/`、`scripts/`、`tests/`：部署入口、自动化门禁和跨应用证据。

## 不变量

- 每个触碰租户内容的查询和 mutation 都强制 `space_id`；引用必须保留结构化 provenance。
- 云端数据出境必须按空间显式 opt-in，禁止本地路由静默回退到云端。
- retrieval、prompt、chunking、parser、embedding 或 rerank 变更必须运行离线评估并记录配置版本。
- 数据库迁移 append-only；共享契约、迁移序号、根构建文件和治理记录一次只允许一个 owner。

## 验证与交付

- 按卡片执行最小但完整的格式、单元、集成、契约、安全和评估门禁；超过约 50 行的输出保存到 Ticket 指定 evidence 文件，只在回报中写摘要。
- 提交使用中文 Conventional Commit：`<type>(<scope>): <中文摘要>`；只显式暂存本卡文件，提交正文包含真实验证、风险、回滚和下一步。
- 没有独立 Ubuntu、凭据、许可证接受、生产迁移或 release 审批时停止并报告，不用替代环境冒充验收。

## 参考入口

- 当前代码：[`doc-1`](<../../../backlog/docs/architecture/doc-1 - 当前代码.md>)
- 运行与安全：[`doc-2`](<../../../backlog/docs/operations/doc-2 - 运行与安全.md>)
- 任务工作流：[`doc-3`](<../../../backlog/docs/operations/doc-3 - 任务与文档工作流.md>)
- 优化方向：[`doc-4`](<../../../backlog/docs/architecture/doc-4 - 业务闭环优化方向.md>)
