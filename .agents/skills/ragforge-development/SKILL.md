---
name: ragforge-development
description: Implement, test, review, or reorganize RAGForge source, contracts, deployment, and project records. Use for work inside this repository; do not use for unrelated general questions.
---

# RAGForge Development

把 RAGForge 作为“模块化单体 + 独立 ingestion worker”的商业级 RAG 工程维护。仓库级硬约束以根目录 `AGENTS.md` 为准；本 Skill 只补充任务路由和上下文选择，不能扩大授权。

## 开始前

1. 日常任务先读 [`docs/08-records/AGENT_STATE_CARD.md`](../../../docs/08-records/AGENT_STATE_CARD.md) 和 [`docs/08-records/TASK_BOARD.md`](../../../docs/08-records/TASK_BOARD.md)。只有审计、阶段闭环或状态冲突时才读取 `PROJECT_STATUS.md`、路线图和阶段计划。
2. 执行 `git status --short --branch`，保留用户已有改动；按任务板的卡片、Ticket、ownership 和预算工作。
3. Worker 只读 Ticket 的 allow-list，不通过全仓搜索补上下文；Orchestrator 负责分派、合并、状态卡和阶段闭环。

## 按领域路由

- `backend/server/`：同步 API、业务规则、空间权限、检索与回答编排。
- `backend/ingestion-worker/`：异步来源同步、解析、分块、向量和候选索引；不绕过 Server 直接成为第二业务后端。
- `frontend/ragforge-web/`：角色感知 SPA；用户路径必须从空间、来源、索引、问答和管理闭环验证。
- `backend/ai-runtime/`：仅 OCR、rerank 等窄职责运行时能力。
- `contracts/`：OpenAPI、事件和跨语言 schema 的唯一契约源；先契约后 producer/consumer。
- `deploy/`、`scripts/`、`tests/`：部署入口、自动化门禁和跨应用证据；生成物与真实凭据不得入仓。

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

- 人类入口：[`docs/00-governance/START_HERE.md`](../../../docs/00-governance/START_HERE.md)
- Agent 循环：[`docs/00-governance/AGENT_LOOP_PROMPT.md`](../../../docs/00-governance/AGENT_LOOP_PROMPT.md)
- 架构基线：[`docs/02-architecture/ARCHITECTURE.md`](../../../docs/02-architecture/ARCHITECTURE.md)
- 交付路线：[`docs/03-delivery/ROADMAP.md`](../../../docs/03-delivery/ROADMAP.md)
