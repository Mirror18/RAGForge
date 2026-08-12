# 项目状态

- Updated: 2026-08-12
- Current stage: Foundation / Phase 1 Ready
- Repository: local private Git repository
- Branch: `main`
- External remote: not configured

## 1. 已完成

- 独立 RAGForge 目录和本地 Git 仓库初始化。
- 产品章程、PRD、用户故事和非功能边界。
- 总体/领域/摄取/检索/Provider/API 架构基线。
- 9 项 Accepted ADR。
- Phase 0–7 路线、开发流程和完成定义。
- 测试、RAG 评估、性能、部署、观测、恢复、安全和开源合规计划。
- GitHub 成熟项目比较、引用清单和上游复用登记表。
- 风险与需求追溯基线。
- 多 Agent 一任务一分支一 worktree 的协作、集成与中文提交规则。
- 可直接复用的多 Agent 循环执行提示词。
- Phase 0 可复现验收资产、33 条问题和真实 benchmark 结果；见 [`PHASE_0_BENCHMARK_RESULTS.md`](phase-0/PHASE_0_BENCHMARK_RESULTS.md)。
- Phase 0 上游许可证、精确 commit、LICENSE/NOTICE 和 use mode 闸门；见 [`UPSTREAM_REUSE_REGISTER.md`](../07-research/UPSTREAM_REUSE_REGISTER.md)。
- Phase 0 checklist、风险、追溯矩阵和 retrospective 已闭环。

## 2. 当前声明

- 尚未开发 RAGForge 业务代码；竞品 benchmark 已在独立 Compose/容器中完成，临时探针未进入仓库，RAGFlow dataset 中的探针已删除。
- 尚未复制任何第三方源码。
- 尚未选择根级开源许可证。
- 尚未创建 GitHub remote 或 release。
- Obsidian 仓库没有被写入项目进度。
- Phase 0 实验发现的跨空间、provenance、OCR、重复 basename 和重启风险仍为开放/缓解中状态，不得视为产品通过安全验收。

## 3. 下一入口

进入 Phase 1 工程与领域骨架：先落实 `space_id` 安全边界、API/event contract、稳定 provenance/citation 和本地 provider contract，再扩展 ingestion/retrieval。Phase 0 关闭证据见 [`PHASE_0_RETROSPECTIVE.md`](retrospectives/PHASE_0_RETROSPECTIVE.md)。

## 4. 更新规则

阶段状态、阻塞、退出证据和下一动作先更新本文件；阶段复盘保存在 `retrospectives/`。项目进入稳定开发后，再决定将何种摘要同步到 Obsidian。
