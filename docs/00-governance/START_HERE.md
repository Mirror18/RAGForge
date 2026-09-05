# RAGForge 从这里开始

这是人类和 Agent 共用的项目入口。这里不复制完整状态、任务验收或历史证据；它只告诉你应该去哪里看、下一步能做什么。

## 现在的结论

RAGForge 当前处于 Phase 7 `p2-execution`。P0/P1 的主要产品与回归门禁已经完成，Phase 7 P2 的部署链路仍未闭环。当前最重要的阻塞不是“再写一个功能”，而是缺少一套独立、干净的 Ubuntu 24.04 WSL/VM 来执行部署验收。

具体状态、基线 SHA、完成卡片和阻塞证据以[状态卡](../08-records/AGENT_STATE_CARD.md)及[项目状态](../08-records/PROJECT_STATUS.md)为准。

任务目标与完成效果先看[任务板的人类任务总览](../08-records/TASK_BOARD.md#05-人类任务总览先看这里)；具体实施记录不堆在文档里，而是在对应 Git commit body 中。

## 你下一步应该做什么

### 如果要继续主线交付

1. 准备独立 Ubuntu 24.04 WSL/VM，确保不继承现有 Compose 卷和端口。
2. 环境可用后，按任务板卡片 `P7D-03` 执行“从零部署 → RAG 业务闭环 smoke → 结构化证据”。
3. `P7D-03` 通过后，再按依赖顺序进入观测、升级/回滚、公共化和阶段闭环；不要提前宣称 Phase 7 完成。

### 如果暂时没有 Ubuntu 环境

保持 `P7D-03` 为 `BLOCKED`，不要用 Windows 共享卷替代验收，也不要启动依赖它的 P7D-04～07。若要继续架构演进，先在[任务板](../08-records/TASK_BOARD.md)拆出明确 ownership、契约、迁移和验收条件的实现卡；ADR-0013 已接受，但“已接受”不等于代码已实现。

## Agent 每轮只走这条路径

1. 读[状态卡](../08-records/AGENT_STATE_CARD.md)：知道当前阶段、基线和依赖图。
2. 读[任务板](../08-records/TASK_BOARD.md)：选择一张有依赖满足、ownership 明确、预算明确的卡片。
3. Worker 只读自己的 Ticket；Orchestrator 负责 worktree、合并、证据和状态卡回写。
4. 需要角色流程时读[多 Agent 循环提示词](AGENT_LOOP_PROMPT.md)；需要硬规则时读根目录 [`AGENTS.md`](../../AGENTS.md)。

任务完成后用 `git show --format=fuller <完成SHA>` 查看真实改动和验证；不知道 SHA 时，用 `git log --all --oneline --grep='<任务ID>'` 搜索。

## 文档权威顺序

| 问题 | 入口 |
|---|---|
| 我们做什么 | [项目章程](PROJECT_CHARTER.md) → [PRD](../01-product/PRD.md) |
| 代码为什么这样分 | [总体架构](../02-architecture/ARCHITECTURE.md) → [ADR](../02-architecture/adr/README.md) |
| 现在做什么 | [状态卡](../08-records/AGENT_STATE_CARD.md) → [任务板](../08-records/TASK_BOARD.md) |
| 如何验收 | [Definition of Done](../03-delivery/DEFINITION_OF_DONE.md) → 对应阶段清单/证据 |
| 如何运行 | [部署与运行](../05-operations/DEPLOYMENT.md) → [部署资产索引](../../deploy/README.md) |
| 安全与合规 | [安全基线](../06-security-compliance/SECURITY_BASELINE.md) → [风险登记表](../08-records/RISK_REGISTER.md) |

## 目录边界

`frontend/` 只放前端应用；`backend/` 只放后端运行面。`contracts/` 是跨应用公开契约；`tests/` 只放跨应用、契约、验收、安全和评估测试；`fixtures/` 只放可公开、可销毁的验证数据；`scripts/` 只放仓库级开发、CI、评估和运维脚本。`deploy/` 是运行入口，`docs/` 是产品、架构、交付和证据。

更完整的目录职责、启动方式和文档顺序见[项目手册](PROJECT_MANUAL.md)。
