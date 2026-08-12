# 参与开发

## 1. 工作流

1. 从一个可追溯的 Issue、用户故事、风险或 ADR 开始。
2. 小步提交一个纵向能力，不以“后端完成但无法验收”为交付状态。
3. 涉及外部接口时，先修改 `contracts/`，再实现消费者和提供者。
4. 涉及 RAG 行为时，提交前运行离线评估并保存对照结果。
5. 更新必要文档、迁移脚本、监控、Runbook 和回滚说明。

## 2. 分支与提交

- 默认分支：`main`，必须保持可构建、可部署。
- 单人功能分支可使用 `feature/<issue>-<topic>` / `fix/<issue>-<topic>`；多 Agent 并行分支统一使用 `codex/<phase>-<task>-<agent>`。
- 多 Agent 任务必须使用独立 Git worktree，详细所有权、集成和清理规则以 [AGENTS.md](AGENTS.md) 为准。
- 提交使用“英文 Conventional Commit 类型/范围 + 中文摘要”，例如 `feat(ingestion): 完成 Git 数据源增量检查点`。
- 每个满足验收条件的任务阶段必须提交；禁止用 `wip` 或模糊摘要代替阶段成果。
- 禁止强推共享分支；禁止在同一提交混入无关格式化。

## 3. Pull Request 最低内容

- 需求和方案摘要。
- 风险、安全、数据迁移、兼容性影响。
- 自动化测试与人工验收证据。
- RAG 变更的基准结果。
- 部署、回滚和观测方式。
- 新增第三方依赖的许可证结论。

完整标准见 [开发流程](docs/03-delivery/DEVELOPMENT_WORKFLOW.md) 和 [完成定义](docs/03-delivery/DEFINITION_OF_DONE.md)。
