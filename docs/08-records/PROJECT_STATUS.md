# 项目状态

- Updated: 2026-08-13
- Current stage: Phase 2 Provider、Prompt 与 Run 完成；Phase 3 Ready
- Repository: GitHub `Mirror18/RAGForge`
- Branch: `main`
- External remote: `origin` configured; Phase 1 CI Run [31616214088](https://github.com/Mirror18/RAGForge/actions/runs/31616214088) succeeded；Phase 2 CI workflow 已增加云协议、并发和出境门禁，等待下一次 push 后产生 GitHub Run

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
- Phase 1 工程/领域骨架已实现：契约、Java server/worker、Vue/Python 骨架、core Compose、Flyway、Valkey Session、CSRF、空间 RBAC、audit/outbox、幂等和 CI 门禁；见 [`PHASE_1_IMPLEMENTATION_RESULTS.md`](phase-1/PHASE_1_IMPLEMENTATION_RESULTS.md)。
- Phase 1 本地 Compose 启动、健康、备份冒烟、跨空间 API smoke、Python contract 和前端门禁已取得证据；见 [`PHASE_1_CHECKLIST.md`](../03-delivery/PHASE_1_CHECKLIST.md)。
- Phase 2 Provider、Prompt、Space Binding、Run/Step/SSE、取消/重试和 usage ledger 已实现并通过全量 Maven 84/84；见 [`PHASE_2_CHECKLIST.md`](../03-delivery/PHASE_2_CHECKLIST.md)。
- Phase 2 本地真实 Ollama `qwen3.5:9b` Run 全链路验收已通过；Run、Step、ModelInvocation、Usage Ledger 均成功，证据见 [`phase2-local-ollama-run.json`](../../tests/evidence/phase2-local-ollama-run.json)。
- Phase 2 Mock 云协议 4/4、20 链路并发 1/1、出境隔离 5/5、契约 25/25 已通过；workflow 已将三组 deterministic gate 接入 CI。

## 2. 当前声明

- Phase 2 只完成 Provider、Prompt 和 no-RAG Run 纵向切片；尚未开发版本化摄取、检索、引用回答等 RAG 业务能力。
- 尚未复制任何第三方源码。
- 尚未选择根级开源许可证。
- 已配置 GitHub remote `Mirror18/RAGForge`；Phase 1 CI Run 31616214088 成功，SBOM artifact 和 Grype 扫描均有证据；Phase 2 workflow 变更已合入本地 main，尚未 push、尚未创建 release。
- Obsidian 仓库没有被写入项目进度。
- Phase 0 实验发现的跨空间、provenance、OCR、重复 basename 和重启风险仍为开放/缓解中状态，不得视为产品通过安全验收。
- 本机 Java 21 全量 Maven Testcontainers 已通过（84 tests，0 failures/errors/skips）；格式、架构、secret、Markdown link、契约 25/25、Phase 2 Python 验收均通过。Testcontainers/Valkey 日志中的关闭后重连 warning 不影响测试结果，但保留为后续测试生命周期清理项。

## 3. 下一入口

Phase 2 已闭环，下一入口为 Phase 3 版本化摄取流水线。Phase 0 关闭证据见 [`PHASE_0_RETROSPECTIVE.md`](retrospectives/PHASE_0_RETROSPECTIVE.md)，Phase 1 复盘见 [`PHASE_1_RETROSPECTIVE.md`](retrospectives/PHASE_1_RETROSPECTIVE.md)，Phase 2 复盘见 [`PHASE_2_RETROSPECTIVE.md`](retrospectives/PHASE_2_RETROSPECTIVE.md)。

## 4. 更新规则

阶段状态、阻塞、退出证据和下一动作先更新本文件；阶段复盘保存在 `retrospectives/`。项目进入稳定开发后，再决定将何种摘要同步到 Obsidian。
