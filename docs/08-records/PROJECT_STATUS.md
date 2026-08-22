# 项目状态

- Updated: 2026-08-22
- Current stage: Phase 5 带引用问答与只读 Agent 已完成本地实现/安全/质量门禁；typed authorization、既有 provider connection embedding、版本化 revision/artifact material service 和显式 opt-in production Spring graph 已合入，但真实 provider route/credential 配置与 RAG E2E 证据缺失，阶段保持 blocked
- Repository: GitHub `Mirror18/RAGForge`
- Branch: `main`
- External remote: `origin` configured；Phase 3 OCR runtime implementation 已推送至 `2ca3a75`；GitHub Actions quality Run [31706823033](https://github.com/Mirror18/RAGForge/actions/runs/31706823033) 成功，Phase 3 JVM evidence artifact `9183633612`、Syft SBOM artifact `9183518984`、Grype SARIF artifact `9183542524` 已生成。

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
- Phase 3 SourceConnector、版本化 schema/V8 migration、Outbox/RabbitMQ/worker 幂等、文件/本地目录/Git connector、原生解析/真实 Tesseract OCR 和 Local/MinIO 对象存储已合入 main；实现合并提交为 `ad91c515fa83ec62627903a8a39a65a8f21f3b0d`，真实 OCR 任务最终合并提交为 `2ca3a75`。
- Phase 3 P3-CONTRACT-01 至 P3-CONTRACT-07、P3-EXIT-01 至 P3-EXIT-04 均有可重跑证据；根 Maven reactor 成功、Worker 28/28、Phase 3 contract 7/7、acceptance 2/2、fault/performance、secret/dependency/format/link gates 均通过，证据见 [`phase3-acceptance-summary.json`](../../tests/evidence/phase3-acceptance-summary.json) 与 [`phase3-ocr-runtime-summary.json`](../../tests/evidence/phase3-ocr-runtime-summary.json)。

## 2. 当前声明

- Phase 3 已完成阶段闭环：原生格式 6/6、image-only PDF 2/2、真实 Tesseract OCR 2/2；检索、分块、引用回答仍未进入本阶段。
- Phase 4 已完成阶段闭环（2026-08-21）：P4-D 父子分块、P4-E embedding cache/Qdrant candidate index、P4-F dense/BM25/RRF/rerank/parent expansion、P4-G Chunk Studio/Retrieval Playground 与 P4-H 评测/规模/证据已合入 main。阶段合并提交包括 `300569b`、`ab81ed1`、`1f6450e`、`fed0034`、`041bf34`、`e27ae75`、`ca6db93` 及其对应 worker commits。30 问 Recall@10 `0.965517`、MRR@10 `0.827586`；Qdrant 1M synthetic child points Recall@10 `1.0`、p95 `1101.3382 ms`；空间过滤/索引切换回滚/override 冲突 targeted Maven 17/17。证据见 [`PHASE_4_CHECKLIST.md`](../03-delivery/PHASE_4_CHECKLIST.md)、[`phase4-retrieval-benchmark.json`](../../tests/evidence/phase4-retrieval-benchmark.json)、[`phase4-1m-qdrant.json`](../../tests/evidence/phase4-1m-qdrant.json) 和 [`phase4-isolation-and-override.json`](../../tests/evidence/phase4-isolation-and-override.json)。技术基线维持 Java 21 + Spring Boot 3.5.x，本阶段未升级 Java/Boot。
- 尚未复制任何第三方源码。
- 尚未选择根级开源许可证。
- 本轮 Phase 5 实现合并提交：material worker `624c6df` / no-ff merge `49368e4`，graph worker `c874df3` / no-ff merge `49d9160`；授权上下文、embedding route/provider adapter、版本化材料读取服务、prompt hash resolver、active retrieval identity 和 opt-in production graph 及回归测试均已纳入。
- 已配置 GitHub remote `Mirror18/RAGForge`；Phase 4 当前实现已推送至 `origin/main`，GitHub Actions quality Run [32450998792](https://github.com/Mirror18/RAGForge/actions/runs/32450998792) 对 `9d21b48` 全绿（4m19s），Phase 4 evidence artifact `9435734012`、SBOM artifact `9435662885`、Grype SARIF artifact `9435676463` 已生成；尚未创建 release。GitHub Actions Syft/Grype 仍是正式发布前的有效 SBOM/SCA 门禁。
- Obsidian 仓库没有被写入项目进度。
- Phase 0 实验发现的 provenance、恶意文件、重复 basename 和重启风险仍为开放/缓解中状态；Phase 4 已关闭 candidate index 半构建发布风险的阶段范围，并验证 chunk/Qdrant/cache 的空间边界；Phase 5 已完成 citation/agent 访问边界的本地验证，并实现 typed session-to-space authorizer、既有 provider adapter embedding、空间绑定的版本化 material service、prompt hash resolver 和 active retrieval identity。真实 graph 仅显式对象存储启用时组装；受控 provider route/credential 数据和真实 RAG E2E 证据尚未具备。OCR runtime 可用性风险 R-022 已关闭，生产 quarantine/AV/sandbox 风险 R-006 仍开放。
- 本机 Java 21 根 Maven Testcontainers 已通过，Worker 28/28、Phase 3 Python acceptance 2/2；格式、架构、secret、Markdown link、依赖清单、contract 32/32 均通过。测试日志中的 Testcontainers/Valkey 关闭后重连 warning 不影响测试结果，但保留为后续生命周期清理项。

## 3. Phase 4 闭环摘要

- P4-CONTRACT-01 至 P4-CONTRACT-06 全部满足；contract test 42/42。
- P4-EXIT-01 至 P4-EXIT-04 全部满足；退出证据均为仓库内 JSON、测试或脚本，可重跑且不含生产数据。
- 根 Maven `BUILD SUCCESS`；Phase 4 targeted Maven 17/17；format、architecture、Markdown link、secret、dependency inventory、Compose、web format/build 通过。CI workflow 已加入 Phase 4 deterministic benchmark gate；Qdrant 1M 演练为本地 Docker 受控容量证据，不作为 CI 每次运行的负载测试。
- 保留风险：BM25 当前为进程内确定性实现，durable lexical provider 需后续架构选择；1M Qdrant 证据为 8 维合成向量，生产 embedding 维度/并发/混合负载仍需容量复测；全量 120+ 评估与 citation/agent 安全门禁属于 Phase 5/6。

## 3. Phase 5 当前闭环与证据（2026-08-22）

- 当前 HEAD：`49d9160`；本轮 material worker `624c6df` / merge `49368e4`，graph worker `c874df3` / merge `49d9160`，均保留任务提交历史。
- 合同：`python scripts/ci/contract_test.py` 检查 21 artifacts、52 tests；Phase 5 定向 contract 10/10。
- 质量：[`phase5-generation-evaluation.json`](../../tests/evidence/phase5-generation-evaluation.json) 的合成 12 cases candidate citation precision/faithfulness/abstention accuracy 均为 `1.0`；Phase 6 仍需 120+ 和人工评估。
- 安全：[`phase5-security.json`](../../tests/evidence/phase5-security.json) 的 AgentToolSecurity 9/9、回答/出境 19/19，未授权云调用、跨空间泄漏、Evidence 外引用、SSRF 绕过、Shell/SQL/外部写入、敏感审计字段均为 `0`。
- 性能：[`phase5-performance.json`](../../tests/evidence/phase5-performance.json) 为版本化合成 fixture，E2E p50/p95 `79.7/88.8ms`、TTFT p50 `29.4ms`、input/output `1828/419`、估算成本 `0.008`；retrieval/generation 指标是代理测量，不是生产容量承诺。
- 全量本地门禁：此前根 Maven Server+Worker `28/28`、Flyway V1–V12、Web `tsc --noEmit`/`vite build`、format/architecture/Markdown/secret/dependency/Compose/contract/Phase 2 security/Phase 4 evaluation 均通过；本轮 main 合并后 server 全量 `196/196`（0 failures/errors/skips）。
- CI：GitHub Actions quality Run [`32549602459`](https://github.com/Mirror18/RAGForge/actions/runs/32549602459) 对 `0c13eb0` 全绿（4m58s），包含 Maven、Phase 5 生成/性能/安全、证据上传、Phase 3/4、Web、Syft SBOM 与 Grype。
- 本轮本地门禁：`mvn -f pom.xml -pl apps/server -am test`（JDK 21）`196/196` 通过；新增 prompt space/hash resolver、retrieval identity、production graph 条件接线相关测试与材料服务已包含在回归中。真实 provider 凭据、真实 RAG E2E 和云出境未在本轮启用。
- 阶段结论：P5 功能、安全边界、typed authorization、embedding/provider/material seam、完整 opt-in graph 已落地，但默认回答仍 fail-closed，不能把合成 provider/material 测试当作真实可用回答；阶段状态仍为 blocked。[`ADR-0010`](../02-architecture/adr/0010-phase5-provider-material-composition.md) 仍为 Proposed，用户已选择 A、复用既有 provider connection、由 revision/artifact service 提供材料，但仍需受控 route/credential 数据和真实 E2E 证据。

## 4. 下一入口

Phase 5 继续入口为受控 provider route/credential 配置审查、真实空间级 RAG E2E 和新增 CI Run 证据；ADR-0010 仍需人工接受后才可作为绑定决策。随后进入 Phase 6 的 120+ 评估、SSE 重启恢复和容量/成本演练。必须继续保持 `space_id`、revision/artifact immutable、provenance、Evidence 外引用零容忍和 at-least-once 幂等边界。Phase 5 执行计划与 Checklist 见 [`PHASE_5_EXECUTION_PLAN.md`](phase-5/PHASE_5_EXECUTION_PLAN.md) 与 [`PHASE_5_CHECKLIST.md`](../03-delivery/PHASE_5_CHECKLIST.md)；Phase 5 阶段复盘见 [`PHASE_5_RETROSPECTIVE.md`](retrospectives/PHASE_5_RETROSPECTIVE.md)；既有 Phase 3/4 复盘继续保留。

## 5. 更新规则

阶段状态、阻塞、退出证据和下一动作先更新本文件；阶段复盘保存在 `retrospectives/`。项目进入稳定开发后，再决定将何种摘要同步到 Obsidian。
