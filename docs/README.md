# RAGForge 文档索引

先读[从这里开始](00-governance/START_HERE.md)，再按[项目手册](00-governance/PROJECT_MANUAL.md)的顺序阅读。本索引负责导航，不复制状态和任务验收；同一事实只在其权威文档维护。

## 文档分区

| 分区 | 内容 | 入口 |
|---|---|---|
| `00-governance` | 项目章程、授权、Agent 工作流和协作约定 | [START_HERE](00-governance/START_HERE.md) / [项目章程](00-governance/PROJECT_CHARTER.md) |
| `01-product` | 产品目标、范围、角色和用户故事 | [PRD](01-product/PRD.md) |
| `02-architecture` | 总体架构、领域、摄取、检索、API 和 ADR | [总体架构](02-architecture/ARCHITECTURE.md) / [ADR 索引](02-architecture/adr/README.md) |
| `03-delivery` | 路线图、阶段清单、完成定义和开发流程 | [路线图](03-delivery/ROADMAP.md) / [Phase 7](03-delivery/PHASE_7_CHECKLIST.md) |
| `04-quality` | 测试策略、RAG 评估、性能和测试数据政策 | [测试策略](04-quality/TEST_STRATEGY.md) |
| `05-operations` | 部署、备份、观测和 Runbook | [部署设计](05-operations/DEPLOYMENT.md) |
| `06-security-compliance` | 安全基线、威胁、出境、留存和 OSS 合规 | [安全基线](06-security-compliance/SECURITY_BASELINE.md) |
| `07-research` | GitHub 对标、参考资料和上游复用登记 | [GitHub 调研](07-research/GITHUB_BENCHMARK.md) |
| `08-records` | 状态、任务、风险、追溯、阶段计划、证据和复盘 | [状态卡](08-records/AGENT_STATE_CARD.md) / [任务板](08-records/TASK_BOARD.md) |

## 推荐阅读顺序

1. [项目手册](00-governance/PROJECT_MANUAL.md)：项目目标、目录边界、启动方式、文档生成规则和下一步导航。
2. [项目章程](00-governance/PROJECT_CHARTER.md) → [PRD](01-product/PRD.md)：为什么做、做什么、不做什么。
3. [总体架构](02-architecture/ARCHITECTURE.md) → [架构演进](02-architecture/ARCHITECTURE_EVOLUTION.md) → [ADR 索引](02-architecture/adr/README.md)：代码为何这样分。
4. [路线图](03-delivery/ROADMAP.md) → 对应阶段清单 → [测试策略](04-quality/TEST_STRATEGY.md)：如何开发和验收。
5. [部署设计](05-operations/DEPLOYMENT.md) → [`deploy/`](../deploy/README.md)：如何运行、升级和恢复。
6. [安全基线](06-security-compliance/SECURITY_BASELINE.md) → [风险登记表](08-records/RISK_REGISTER.md)：哪些边界不可破坏。
7. [状态卡](08-records/AGENT_STATE_CARD.md) → [任务板](08-records/TASK_BOARD.md)：今天做哪一张卡；完成细节回到 Git history。

## 权威关系

- 硬规则：根目录 [`AGENTS.md`](../AGENTS.md)。
- 日常状态：[`AGENT_STATE_CARD.md`](08-records/AGENT_STATE_CARD.md)。
- 任务定义与预算：[`TASK_BOARD.md`](08-records/TASK_BOARD.md)；Worker 只读自己的 Ticket。
- 任务目标与达成效果：[`TASK_BOARD.md` 人类任务总览](08-records/TASK_BOARD.md#05-人类任务总览先看这里)；任务执行细节、测试和风险以 Git commit body 为准。
- 产品范围：[`PRD.md`](01-product/PRD.md)；架构决策：[`02-architecture/adr/`](02-architecture/adr/)。
- 审计、阶段和证据级记录：[`PROJECT_STATUS.md`](08-records/PROJECT_STATUS.md)、风险表、追溯矩阵和阶段记录。

## 当前架构演进

- [GitHub 知识库对标](07-research/2026-09-05-knowledge-architecture-benchmark.md)
- [目标架构、差距与迁移设计](02-architecture/ARCHITECTURE_EVOLUTION.md)
- [ADR-0013：版本化知识执行](02-architecture/adr/0013-versioned-knowledge-execution.md)

ADR-0013 已接受，但实现仍需拆成有 ownership、契约和测试的任务卡；文档接受不等于运行时完成。

## 如何维护

新需求先更新 PRD、路线图、风险和追溯；架构变化新增 ADR；运行证据放到 `docs/08-records/` 或 `tests/evidence/`；不要把聊天记录、临时草稿或 README 的复制内容当作第二个事实源。
