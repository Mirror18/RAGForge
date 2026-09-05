# 记录归档

`08-records/` 是项目运行记录区，不是另一套产品或架构说明。这里保留会随执行变化的状态、任务、风险、追溯、阶段计划、证据和复盘；稳定的项目说明统一回到 `docs/01`–`docs/07` 主文档。

## 每类记录放在哪里

| 记录 | 入口 | 用途 |
|---|---|---|
| 日常状态 | [AGENT_STATE_CARD.md](./AGENT_STATE_CARD.md) | Agent 每轮首先读取；当前阶段、基线 SHA、阻塞、下一步和派发表 |
| 任务定义 | [TASK_BOARD.md](./TASK_BOARD.md) | 任务目标、达到的效果、依赖、ownership、预算、验收和测试 |
| 审计状态 | [PROJECT_STATUS.md](./PROJECT_STATUS.md) | 阶段闭环、发布治理和状态冲突时的证据级记录 |
| 风险 | [RISK_REGISTER.md](./RISK_REGISTER.md) | 风险编号、状态、缓解、owner 和残余风险 |
| 需求追溯 | [TRACEABILITY_MATRIX.md](./TRACEABILITY_MATRIX.md) | 需求/任务/代码/测试/证据之间的映射 |
| Agent 经验 | [MEMORY.md](./MEMORY.md) | 只沉淀跨会话工程经验，不存放当前状态或任务清单 |
| 阶段计划 | [`phase-*/`](.) | 各阶段冻结的执行计划、依赖、结果和证据入口 |
| 复盘 | [`retrospectives/`](./retrospectives/) | 阶段结束后的事实、质量数据、问题和下一阶段入口 |
| Worker 合同 | [`tickets/`](./tickets/) | 一卡一票据；精确 ownership、read_only、验收和必跑命令 |

## 记录和 Git history 的分工

任务板回答“要做什么、做到什么效果”；Ticket 回答“本次 Worker 被允许怎样做”；Git commit body 回答“实际改了什么、跑了什么验证、有哪些风险和回滚方式”。不再创建单独的任务流水账或重复执行日志。

阶段证据文件放在 `tests/evidence/`，只保存可公开、可复核、已脱敏的结构化结果；原始日志超过约 50 行时保存在被忽略的证据目录，文档和 commit body 只写摘要。

## 当前使用方式

1. 先看 [Agent 状态卡](./AGENT_STATE_CARD.md)。
2. 从 [任务板](./TASK_BOARD.md) 选择依赖满足且 ownership 不冲突的单张卡片。
3. 读取对应 Ticket，按允许的文件和 `tests.must_run` 执行。
4. 合并后由 Orchestrator 更新状态卡；阶段关闭时再更新项目状态、风险、追溯和复盘。

`AGENTS.md` 的硬规则优先于本归档说明；当前主文档入口见 [`docs/README.md`](../README.md)。
