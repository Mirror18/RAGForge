# 项目状态

- Updated: 2026-08-13
- Current stage: Phase 3 版本化摄取流水线已实现，阶段闭环阻塞于真实 OCR runtime
- Repository: GitHub `Mirror18/RAGForge`
- Branch: `main`
- External remote: `origin` configured；Phase 3 main 已推送至 `b2a1fd680f6789deadd3dcb24b44040733415827`；GitHub Actions quality Run [31679337426](https://github.com/Mirror18/RAGForge/actions/runs/31679337426) 成功，Phase 3 JVM evidence artifact `9172912980`、Syft SBOM artifact `9172818407`、Grype SARIF artifact `9172839405` 已生成。

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
- Phase 3 SourceConnector、版本化 schema/V8 migration、Outbox/RabbitMQ/worker 幂等、文件/本地目录/Git connector、原生解析/OCR 边界和 Local/MinIO 对象存储已合入 main；阶段合并提交为 `ad91c515fa83ec62627903a8a39a65a8f21f3b0d`。
- Phase 3 P3-CONTRACT-01 至 P3-CONTRACT-07、P3-EXIT-01、P3-EXIT-02、P3-EXIT-03 已有可重跑证据；全仓库 Maven 26/26、Phase 3 contract 7/7、cross-platform/parser/fault/performance、secret/dependency/format/link gates 均通过，GitHub Actions Linux quality 全步骤通过，证据见 [`phase3-acceptance-summary.json`](../../tests/evidence/phase3-acceptance-summary.json)。

## 2. 当前声明

- Phase 3 尚未完成阶段闭环：P3-EXIT-01 已由 GitHub Linux Run 证据满足；P3-EXIT-04 的真实 OCR 2/2 需要外部 OCR runtime。检索、分块、引用回答仍未进入本阶段。
- 尚未复制任何第三方源码。
- 尚未选择根级开源许可证。
- 已配置 GitHub remote `Mirror18/RAGForge`；本阶段安全修复与记录已推送至 `b2a1fd6`，尚未创建 release。GitHub Actions Syft/Grype 已在 Run `31679337426` 通过，仍是正式发布前的有效 SBOM/SCA 门禁。
- Obsidian 仓库没有被写入项目进度。
- Phase 0 实验发现的跨空间、provenance、OCR、重复 basename 和重启风险仍为开放/缓解中状态，不得视为产品通过安全验收。
- 本机 Java 21 全量 Maven Testcontainers 已通过（Server + Worker 26 tests，0 failures/errors/skips）；格式、架构、secret、Markdown link、依赖清单、Phase 3 contract 7/7、Phase 3 Python acceptance 均通过。测试日志中的 Testcontainers/Valkey 关闭后重连 warning 不影响测试结果，但保留为后续生命周期清理项。

## 3. 下一入口

Phase 3 当前入口为补充真实 OCR runtime；P3-EXIT-01 的 GitHub Linux acceptance 已完成，在 P3-EXIT-04 完成前不得进入 Phase 4。阶段复盘见 [`PHASE_3_RETROSPECTIVE.md`](retrospectives/PHASE_3_RETROSPECTIVE.md)；既有 Phase 0–2 复盘继续保留。

## 4. 更新规则

阶段状态、阻塞、退出证据和下一动作先更新本文件；阶段复盘保存在 `retrospectives/`。项目进入稳定开发后，再决定将何种摘要同步到 Obsidian。
