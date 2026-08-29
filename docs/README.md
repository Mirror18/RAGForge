# RAGForge Documentation Map

| 区域 | 内容 | 主要入口 |
|---|---|---|
| `00-governance` | 目的、边界、工作和授权方式 | [项目章程](00-governance/PROJECT_CHARTER.md) |
| `01-product` | PRD、角色和验收故事 | [PRD](01-product/PRD.md) |
| `02-architecture` | 系统/领域/摄取/检索/API 和 ADR | [总体架构](02-architecture/ARCHITECTURE.md) |
| `03-delivery` | Phase、研发流程、完成定义 | [路线图](03-delivery/ROADMAP.md) / [当前 Phase 7 清单](03-delivery/PHASE_7_CHECKLIST.md) / [多 Agent Loop](03-delivery/MULTI_AGENT_LOOP_PROMPT.md) |
| `04-quality` | 测试、RAG 评估、性能和数据政策 | [测试策略](04-quality/TEST_STRATEGY.md) |
| `05-operations` | 部署、观测、备份和 Runbook | [部署设计](05-operations/DEPLOYMENT.md) |
| `06-security-compliance` | 安全、威胁、出境、开源合规 | [安全基线](06-security-compliance/SECURITY_BASELINE.md) |
| `07-research` | GitHub 项目调研、引用和复用登记 | [基准调研](07-research/GITHUB_BENCHMARK.md) |
| `08-records` | 状态、风险、追溯和阶段复盘 | [项目状态](08-records/PROJECT_STATUS.md) / [风险表](08-records/RISK_REGISTER.md) |

文档状态约定：规划文档描述当前基线；ADR 保存不可变决策历史；records 保存有日期的执行证据。当前阶段为 Phase 7 `implementation-reconciliation`；实现状态必须由代码路径和可重跑测试证明，不能由历史阶段声明、契约占位或 UI 文案推断。
