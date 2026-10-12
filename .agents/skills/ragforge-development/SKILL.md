---
name: ragforge-development
description: Implement, test, review, or reorganize RAGForge source, contracts, deployment, and project records. Use for work inside this repository; do not use for unrelated general questions.
---

# RAGForge Development

把 RAGForge 作为 space / knowledge / answer / gateway 四个业务服务维护（ADR-0014）。仓库级硬约束以根目录 `AGENTS.md` 为准；本 Skill 只补充任务路由和上下文选择，不能扩大授权。M5 任务源是 `specs/` 与 `backlog/`。

## 开始前

1. 日常任务先读 [`docs/08-项目状态卡.md`](../../../docs/08-项目状态卡.md) 和 [`docs/09-项目任务总台账.md`](../../../docs/09-项目任务总台账.md)。只有审计、阶段闭环或状态冲突时才读取 [`docs/10-项目状态审计记录.md`](../../../docs/10-项目状态审计记录.md)、交付主文档的相关章节和对应阶段记录。
2. 执行 `git status --short --branch`，保留用户已有改动；按任务板的卡片、Ticket、ownership 和预算工作。
3. Worker 只读 Ticket 的 allow-list，不通过全仓搜索补上下文；Orchestrator 负责分派、合并、状态卡和阶段闭环。

## 按领域路由

- `backend/space/`：账号、会话、成员、出境策略、云端模型绑定。
- `backend/knowledge/`：来源、解析、分块、embedding、索引发布与候选检索；job 角色可以是同一套代码的第二进程。
- `backend/answer/`：检索编排、生成、结构化引用、run。
- `backend/gateway/`：浏览器唯一 HTTP 入口。
- `frontend/ragforge-web/`：角色感知 SPA；用户路径必须从空间、模型、资料、索引、问答和管理闭环验证。
- `contracts/`：OpenAPI、事件和跨语言 schema 的唯一契约源；先契约后 producer/consumer。
- `specs/`、`backlog/`：M5 任务源；不要把同一张卡抄进 `docs/09`。
- `deploy/`、`scripts/`、`tests/`：部署入口、自动化门禁和跨应用证据；生成物与真实凭据不得入仓。
- Maven 模块拆分完成前，现有 `backend/server` 与 `backend/ingestion-worker` 仍是可运行进程。

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

- 人类入口：[`docs/00-需求处理与最终效果.md`](../../../docs/00-需求处理与最终效果.md)
- 技术栈与运行：[`docs/01-技术栈与工程运行.md`](../../../docs/01-技术栈与工程运行.md)
- Agent 循环：[`docs/04-交付路线与质量门禁.md`](../../../docs/04-交付路线与质量门禁.md)
- 架构基线：[`docs/02-项目架构与领域模型.md`](../../../docs/02-项目架构与领域模型.md)
- 交付路线：[`docs/04-交付路线与质量门禁.md`](../../../docs/04-交付路线与质量门禁.md)
