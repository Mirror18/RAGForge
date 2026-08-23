# 项目状态

- Updated: 2026-08-23
- Current stage: Phase 6 评估、观测、安全与恢复已完成（`completed-with-explicit-waiver`）；Phase 5 已闭环，ADR-0010 已接受并采用方案 A typed authorization context，复用既有 provider connection，由 revision/artifact service 提供 material，用户授权本地 Ollama `LOCAL_ONLY` route 完成真实 RAG E2E
- Repository: GitHub `Mirror18/RAGForge`
- Branch: `main`
- 当前权威状态（覆盖本文档中的历史基线行）：功能验证基线为 `bde93ebe9be2b0b9e2614a0cc43baf216285c1b6`，其 GitHub Actions quality Run [`32586867110`](https://github.com/Mirror18/RAGForge/actions/runs/32586867110) 全绿；随后记录提交 `6ec5d9d` 的 quality Run [`32587259456`](https://github.com/Mirror18/RAGForge/actions/runs/32587259456) 亦全绿。两次运行均覆盖静态、契约、SBOM/Grype、Maven、Phase 3–5、评估、安全、性能、Web 门禁；旧 SHA/旧 CI 行仅保留为历史记录。
- 本轮记录：功能合并基线为 `07f973c84fa60dd239ed5c60a443e1edbb801eed`，包含真实 RAG graph stream 与本地并发成本证据；阶段记录提交及其 CI/SBOM/Grype 结果已在下方补记。上方历史远端行保留为上一已知 CI 基线，不能作为本轮提交验证。
- 本轮 CI 已补记：GitHub Actions quality Run [`32579989036`](https://github.com/Mirror18/RAGForge/actions/runs/32579989036) 对提交 `4481bef34cdeed59068b45d03f8a5abbc48bb379` 全绿；SBOM `9477533715`、Grype SARIF `9477541287`、Phase 3 JVM `9477586833`、Phase 4 retrieval `9477586457`、Phase 5 evidence `9477579925` 均已生成。
- 最新功能/阶段证据基线：`462c7a5fded50e4a39e9ee99c26f5254da5c8788`；其 GitHub Actions quality Run [`32580715731`](https://github.com/Mirror18/RAGForge/actions/runs/32580715731) 全绿，SBOM `9477714534`、Grype SARIF `9477725361`、Phase 3 JVM `9477775662`、Phase 4 retrieval `9477775256`、Phase 5 evidence `9477766441` 已生成。后续仅记录性提交不改变该功能验证基线。
- External remote: `origin` configured；当前 `main`/`origin/main` 为 `a80321a5507181cbf3ebb5554781522431fe5dff`；GitHub Actions quality Run [32577917976](https://github.com/Mirror18/RAGForge/actions/runs/32577917976) 全绿（4m52s），SBOM artifact `9477027172`、Grype SARIF `9477036384`、Phase 3 JVM `9477081726`、Phase 4 retrieval `9477081302`、Phase 5 evidence `9477073334` 已生成。历史 Phase 3 OCR、SBOM 和 Grype artifact 仍按各自阶段记录保留；尚未创建 release。

## 1. 已完成

- 独立 RAGForge 目录和本地 Git 仓库初始化。
- 产品章程、PRD、用户故事和非功能边界。
- 总体/领域/摄取/检索/Provider/API 架构基线。
- 11 项 Accepted ADR（包括 ADR-0010、ADR-0011；ADR README 与各 ADR 状态一致）。
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
- 本轮 Phase 5 实现合并提交：material worker `624c6df` / no-ff merge `49368e4`，graph worker `c874df3` / no-ff merge `49d9160`；授权上下文、embedding route/provider adapter、版本化材料读取服务、prompt hash resolver、active retrieval identity 和 opt-in production graph 及回归测试均已纳入。Phase 5 阶段闭环提交为 `4e04771`，远程记录提交为 `0fe22db`。
- 已配置 GitHub remote `Mirror18/RAGForge`；Phase 4 当前实现已推送至 `origin/main`，GitHub Actions quality Run [32450998792](https://github.com/Mirror18/RAGForge/actions/runs/32450998792) 对 `9d21b48` 全绿（4m19s），Phase 4 evidence artifact `9435734012`、SBOM artifact `9435662885`、Grype SARIF artifact `9435676463` 已生成；尚未创建 release。GitHub Actions Syft/Grype 仍是正式发布前的有效 SBOM/SCA 门禁。
- Obsidian 仓库没有被写入项目进度。
- Phase 0 实验发现的 provenance、恶意文件、重复 basename 和重启风险仍为开放/缓解中状态；Phase 4 已关闭 candidate index 半构建发布风险的阶段范围，并验证 chunk/Qdrant/cache 的空间边界；Phase 5 已完成 citation/agent 访问边界、真实 LOCAL_ONLY RAG E2E 和 generation audit。Phase 6 继续收敛评估规模、观测/告警、上传与提示注入安全、隔离恢复、真实 embedding 容量和 retention/deletion。OCR runtime 可用性风险 R-022 已关闭，生产 quarantine/AV/sandbox 风险 R-006 仍开放。
- 本机 Java 21 根 Maven Testcontainers 已通过，Worker 28/28、Phase 3 Python acceptance 2/2；格式、架构、secret、Markdown link、依赖清单、contract 32/32 均通过。测试日志中的 Testcontainers/Valkey 关闭后重连 warning 不影响测试结果，但保留为后续生命周期清理项。

## 3. Phase 4 闭环摘要

- P4-CONTRACT-01 至 P4-CONTRACT-06 全部满足；contract test 42/42。
- P4-EXIT-01 至 P4-EXIT-04 全部满足；退出证据均为仓库内 JSON、测试或脚本，可重跑且不含生产数据。
- 根 Maven `BUILD SUCCESS`；Phase 4 targeted Maven 17/17；format、architecture、Markdown link、secret、dependency inventory、Compose、web format/build 通过。CI workflow 已加入 Phase 4 deterministic benchmark gate；Qdrant 1M 演练为本地 Docker 受控容量证据，不作为 CI 每次运行的负载测试。
- 保留风险：BM25 当前为进程内确定性实现，durable lexical provider 需后续架构选择；1M Qdrant 证据为 8 维合成向量，生产 embedding 维度/并发/混合负载仍需容量复测；全量 120+ 评估与 citation/agent 安全门禁属于 Phase 5/6。

## 3. Phase 5 当前闭环与证据（2026-08-22）

- 当前代码验收基线：`600960f`；ADR-0010 接受提交 `246f993`，真实 Ollama RAG 生成审计提交 `600960f`。
- 合同：`python scripts/ci/contract_test.py` 检查 21 artifacts、52 tests；Phase 5 定向 contract 10/10。
- 质量：[`phase5-generation-evaluation.json`](../../tests/evidence/phase5-generation-evaluation.json) 的合成 12 cases candidate citation precision/faithfulness/abstention accuracy 均为 `1.0`；Phase 6 仍需 120+ 和人工评估。
- 安全：[`phase5-security.json`](../../tests/evidence/phase5-security.json) 的 AgentToolSecurity 9/9、回答/出境 19/19，未授权云调用、跨空间泄漏、Evidence 外引用、SSRF 绕过、Shell/SQL/外部写入、敏感审计字段均为 `0`。
- 性能：[`phase5-performance.json`](../../tests/evidence/phase5-performance.json) 为版本化合成 fixture，E2E p50/p95 `79.7/88.8ms`、TTFT p50 `29.4ms`、input/output `1828/419`、估算成本 `0.008`；retrieval/generation 指标是代理测量，不是生产容量承诺。
- 真实 RAG：[`phase5-real-ollama-rag-e2e.v1.json`](../../tests/evidence/phase5-real-ollama-rag-e2e.v1.json) 记录本地 Ollama `qwen3.5:9b`/`nomic-embed-text:latest` digest、`LOCAL_ONLY`、revision/artifact material、citation/provenance、usage 和 `space_id` 验证；retrieval/generation/E2E `129.0/3986.7/6423.9ms`，input/output/total `196/101/297`，provider call/invocation `1/1`，timeout/retry/degraded/cancel `0`。
- 事件恢复：[`phase5-run-events-restart-cancel.v1.json`](../../tests/evidence/phase5-run-events-restart-cancel.v1.json) 记录真实 server 进程重启、健康检查 `200`、新 store durable replay、cursor/序列/事件身份、取消幂等及 late delta 拒绝。
- 全量本地门禁：此前根 Maven Server+Worker `28/28`、Flyway V1–V13、Web `tsc --noEmit`/`vite build`、format/architecture/Markdown/secret/dependency/Compose/contract/Phase 2 security/Phase 4 evaluation 均通过；本轮加入 Flyway V13 durable run events 后 server 全量 `198/198`（0 failures/errors/skips），新增 durable replay 在隔离 Testcontainers PostgreSQL/Valkey 上通过。
- CI：GitHub Actions quality Run [`32560686933`](https://github.com/Mirror18/RAGForge/actions/runs/32560686933) 对阶段闭环提交 `4e04771` 全绿（4m37s），包含 Maven、Phase 5 生成/性能/安全、证据上传、Phase 3/4、Web、Syft SBOM 与 Grype；SBOM artifact `9472682673`、Grype SARIF `9472691197`、Phase 5 evidence `9472724446`。
- 本轮本地门禁：根 `mvn -q test`（JDK 21）reports 汇总 `227` tests、`0` failures、`0` errors、`1` skipped；新增 prompt space/hash resolver、retrieval identity、production graph 条件接线、durable run event replay、generation audit 和真实 Ollama RAG E2E 均包含在回归中。未提交真实 provider secret，云出境未启用。
- 阶段结论：P5 的合同、引用/拒答、工具安全、SSE replay/cancel、typed authorization、provider/material graph、真实本地 Ollama RAG E2E 和审计 provenance 均已验证；ADR-0010 已 Accepted。真实 E2E 仅授权并覆盖 LOCAL_ONLY 单 fixture，不等同于云出境、生产容量或 Phase 6 评估。

## 4. Phase 6 当前进度与证据（2026-08-23）

- 权威补充：本阶段已完成真实 revision/artifact-backed RAG graph stream boundary 测量（graph-to-first-token `1675.9884ms`、provider TTFT `1560.7450ms`、provider total `4847.3558ms`、wall `4854.6037ms`、usage `193/98/291`）和本地 Ollama 2 并发成本测量（4/4 成功，TTFT p50/p95 `1482.8559/2688.2120ms`、wall p50/p95 `2762.1378/4013.6133ms`、usage `144/108/252`）。旧段落中“同步 RAG graph 集成 TTFT 仍未测量”仅描述旧证据状态，现由 [`phase6-real-ollama-rag-graph-stream.v1.json`](../../tests/evidence/phase6-real-ollama-rag-graph-stream.v1.json) 更新；该证据仍不宣称生产同步 `GenerationPort` 已提供 streaming。

- 本轮增量证据：真实 revision/artifact-backed RAG graph stream 已测得 graph-to-first-token `1675.9884ms`、provider TTFT `1560.7450ms`、provider total `4847.3558ms`、wall `4854.6037ms`、usage `193/98/291`；本地 Ollama 2 并发成本 probe 4/4 成功，TTFT p50/p95 `1482.8559/2688.2120ms`、wall p50/p95 `2762.1378/4013.6133ms`、usage `144/108/252`、估算成本 `0 USD`。两项均为 `LOCAL_ONLY`，不代表生产同步 streaming、云端商业定价或生产语义质量。

- 基线与治理：Phase 6 checklist/执行计划已冻结，统一基线为 `0fe22db5979aa5ae7892165c227a5c8a484bdfb9`；多实例任务已在主线合并提交 `0ff2d13`。ADR-0010 已由用户接受，方案 A、既有 provider connection、revision/artifact material service 和本地 Ollama `LOCAL_ONLY` 授权均已落实；ADR-0011 已于 2026-08-22 由用户接受并按 Valkey live hint + PostgreSQL durable replay 实施。
- 评估：[`phase6-evaluation-dataset.v1.json`](../../tests/evaluation/phase6-evaluation-dataset.v1.json) 有 128 个版本化公共合成用例，runner 校验与 7/7 单元测试通过；candidate report 的确定性指标为 1.0。人工/red-team 门槛由项目用户于 2026-08-23 明确批准豁免，manifest 为 `PASS_WITH_EXPLICIT_WAIVER`，不将 synthetic candidate 当作真实模型质量结论。
- 观测：[`phase6-observability-assets.v1.json`](../../tests/evidence/phase6-observability-assets.v1.json) 与 [`phase6-observability-fault-drill.v1.json`](../../tests/evidence/phase6-observability-fault-drill.v1.json) 已验证 OTel/Prometheus/Grafana/Loki/Tempo profile、dashboard provisioning、trace/log 脱敏和未授权出境告警演练；观测资源测试 3/3 通过。浏览器视觉验收仍未宣称完成。
- 安全与供应链：[`phase6-security.v1.json`](../../tests/evidence/phase6-security.v1.json) 的 Phase 6 corpus、出境回归、Phase 5 合同安全和 AgentToolSecurity 合计 23/23；本轮 [`phase6-redteam-agent-pre-review.v1.json`](../../tests/evidence/phase6-redteam-agent-pre-review.v1.json) 又执行 32 个安全/合同/工具边界测试并全部通过。cross-space、Evidence 外引用、unauthorized cloud、SSRF、Shell/SQL/任意网络/外部写入、解析/OCR 绕过、prompt injection 越权和 raw prompt/provider body 持久化均为 0。最新 GitHub Actions quality workflow [`32577917976`](https://github.com/Mirror18/RAGForge/actions/runs/32577917976) 全绿；该 run 重新执行了 SBOM/Grype、Maven、Phase 3–5、Web 和 Phase 4 门禁。阶段 SBOM artifact `9477027172`、Grype SARIF `9477036384`、Phase 3 JVM `9477081726`、Phase 4 retrieval `9477081302`、Phase 5 evidence `9477073334` 可追溯。Agent-assisted pre-review 明确不替代人工签名。
- 恢复与运维：[`phase6-recovery.v1.json`](../../tests/evidence/phase6-recovery.v1.json) 记录隔离完整恢复、PG 单点、Qdrant 重建、对象缺失/hash、active index 回滚、tombstone/delete ledger 和 outbox/job 幂等；V14 后 RPO `0s`、RTO `11.885s`。retention、space-scoped audit export、cost aggregation 和 SSE event cleanup 已实现，`Phase6OperationsServiceTest` 5/5 通过，且 [`phase6-operations-runtime.v1.json`](../../tests/evidence/phase6-operations-runtime.v1.json) 证明带 `space_id` 的过期 synthetic event 可在隔离 scheduler 中 4 秒内从 1 条清理至 0 条。
- 多实例 live fan-out：[`phase6-multi-instance-run-event-fanout.v1.json`](../../tests/evidence/phase6-multi-instance-run-event-fanout.v1.json) 对两个独立 Spring server context、共享隔离 PostgreSQL/Valkey 完成跨实例投递；同时覆盖提交后发布、回滚不泄漏、同空间/跨空间隔离、重复/乱序补洞、Valkey 暂停后的 PostgreSQL Last-Event-ID durable replay、envelope 最小字段和 listener shutdown。新增 fan-out/双实例测试与完整 server 回归均通过。
- 本轮集成与质量闭环：多实例实现及记录提交 `0ff2d13`/`869fee7` 已推送；取消执行竞态修复 `f02d14d`、事务边界修复 `f086169` 经两个独立 worker 提交后，以 `08b3bfa`、`bde93eb` 非快进合并。基线取消测试在 `eea37ea` 已复现原有竞态；修复后 server `210` tests、根工程 `238` tests 均为 0 failures/0 errors/1 skipped，最新 quality Run `32586867110` 全绿。
- 容量与在线性能：[`phase6-capacity-retrieval-a2.v1.json`](../../tests/evidence/phase6-capacity-retrieval-a2.v1.json) 已真实测得 Ollama `nomic-embed-text:latest` 为 768 维；1,000,000 synthetic child chunks、4 spaces、20 并发混合过滤检索通过，Recall@10 `0.995`、p95 `119.8761ms`、错误率 `0`，满足 P6-OBS-03。该证据仍明确标注向量值为 live dimension 下的公共合成向量，不外推生产语义质量。新的 [`phase6-capacity-online.v1.json`](../../tests/evidence/phase6-capacity-online.v1.json) 在隔离 server 和正式 session 认证 run 上完成 100 次 health API 与 SSE first-event 测量：non-AI p95 `28.7487ms`、SSE first-event p95 `35.9285ms`、错误率均为 `0`，满足 P6-OBS-02。另有 [`phase6-real-ollama-stream-metrics.v1.json`](../../tests/evidence/phase6-real-ollama-stream-metrics.v1.json) 通过 loopback `LOCAL_ONLY` 流式探针测得 standalone TTFT `9130.6742ms`、provider total `11456.3744ms`、wall time `11475.2584ms`、吞吐 `19.6176 tokens/s`、usage `35/46/81`；该证据不冒充同步 RAG graph TTFT，也不代表并发成本模型。
- 真实 RAG 与成本基线：[`phase6-real-ollama-rag-e2e.v1.json`](../../tests/evidence/phase6-real-ollama-rag-e2e.v1.json) 已记录真实本地 Ollama RAG、768 维 embedding、revision/artifact material、citation/provenance、usage 和 `LOCAL_ONLY` 出境约束；[`phase6-cost-local-ollama.v1.json`](../../tests/evidence/phase6-cost-local-ollama.v1.json) 固化了 1 次 provider call、293 tokens、provider-reported usage 和本地估算成本 `0 USD`。该证据满足本轮用户授权的真实 E2E 和本地成本基线，但不替代 Phase 6 人工评估、并发成本模型或云端商业定价。
- 当前阶段结论（历史记录）：P6-C、P6-D、P6-E、P6-OBS-02、P6-OBS-03、standalone 本地流式 TTFT 探针和 P6-G 单实例实现/专项证据已具备；此前 P6-G 多实例 live fan-out 尚未完成。该结论已由后续多实例实现与证据更新。

- 阶段闭环决定（2026-08-23）：自动化、契约、构建、SBOM/Grype、安全、评估、性能、恢复、运维和 Web 门禁均已有通过证据；项目用户明确批准豁免 P6-EVAL-04 的至少 2 名人审 + 1 名红队评审签名门槛。Phase 6 以 `completed-with-explicit-waiver` 关闭；manifest 保留空签名，不声称人工复核已执行；R-005/R-012 作为接受的残余风险保留。

权威更正：上一行“现有同步 RAG graph 的集成 TTFT 仍未测量”属于历史记录；当前证据已由 `phase6-real-ollama-rag-graph-stream.v1.json` 补齐 graph stream boundary。并发成本也已由 `phase6-cost-local-ollama-concurrent.v1.json` 补齐；ADR-0011 决策与多实例演练现已完成。人工/red-team 签名门槛已按用户明确批准豁免，后续高风险 RAG 或安全策略变更必须重新开启复核。

## 5. 下一入口

- 记录更正：本轮已补齐真实 RAG graph stream boundary、本地 2 并发成本证据、ADR-0011 多实例 live fan-out 演练和阶段治理例外记录；下一阶段入口为 Phase 7 Linux 交付与可公开准备。云端/生产级质量与成本仍是本阶段明确边界，不得由本地证据外推。

Phase 6 已完成阶段闭环（显式豁免人工/red-team 门槛）。真实 RAG graph 流式/并发成本、retention/audit/cost/SSE cleanup 和多实例事件扇出演练均已有证据，代码与 CI 质量门禁已闭环。在线 API/SSE 性能门槛和 standalone 本地 Ollama TTFT 已有真实证据；必须继续保持 `space_id`、revision/artifact immutable、provenance、Evidence 外引用零容忍和 at-least-once 幂等边界。Phase 6 执行计划与 Checklist 见 [`PHASE_6_EXECUTION_PLAN.md`](phase-6/PHASE_6_EXECUTION_PLAN.md) 与 [`PHASE_6_CHECKLIST.md`](../03-delivery/PHASE_6_CHECKLIST.md)；下一阶段为 Phase 7，R-005/R-012 需在相关高风险变更时重新开启复核。

## 6. 更新规则

阶段状态、阻塞、退出证据和下一动作先更新本文件；阶段复盘保存在 `retrospectives/`。项目进入稳定开发后，再决定将何种摘要同步到 Obsidian。
