# 项目状态

> 🧭 **AI Agent 入口提示（2026-08-30 起生效）**
>
> 如果你是被指派「日常执行任务」的 AI Agent，请**不要阅读本文件全文**（除非你的任务明确是「审计 / 阶段复盘 / 发布验收」）。
>
> 日常运行的正确入口顺序：
> 1. **先读 [`AGENT_STATE_CARD.md`](AGENT_STATE_CARD.md)** —— 压缩版状态卡，~1k tokens，涵盖基线 SHA / 当前阶段 / 卡片依赖图 / 分派表 / 停机条件。
> 2. 再读 [`TASK_BOARD.md`](TASK_BOARD.md) 中与你职责对应的卡片范围（不要整包复制）。
> 3. Worker 角色只读自己的 Ticket：[`tickets/<CARD_ID>-<slug>.yaml`](tickets/TICKET_TEMPLATE.yaml)。
> 4. 只有当状态卡与代码事实发生冲突，或你的任务属于「阶段验收」「发布治理」时，才回到本文件逐段核对。
>
> 本文件是**治理级 / 证据级权威记录**，保留所有历史阶段的审计证据、基线 SHA、CI 链接与风险说明。日常重复读取会造成显著 Token 浪费（~180 行 / 单轮 8–15k tokens 不等），应当通过状态卡摘要来避免。
>
> 若 `AGENT_STATE_CARD.md` 与本文件存在冲突，**以本文件为准**，并立即通知 Orchestrator Agent 回写修正状态卡。

- Updated: 2026-08-30
- 本轮业务闭环增量：真实浏览器已完成注册/登录、建空间、发布本地 Ollama Profile/Route/Prompt、Markdown 上传、Server→Outbox→RabbitMQ→Worker→MinIO/Qdrant 摄取、Parse Report、候选索引验证/active 发布、LOCAL_ONLY 带引用问答、引用预览、Run/Step/correlationId/usage 展示，以及同文档增量同步后的第二 Revision/Index/Answer；证据见 [`business-loop-e2e.v1.json`](../../tests/evidence/business-loop-e2e.v1.json)。个人 notes 目录已配置为本地约定，但本轮仍未读取个人 notes 内容。
- Current stage: Phase 7 P2 可启动（`p2-ready`）。P7C-01~08、P7C-05R、P7Q-01~06 与 P7Q-05R 已完成；P0/P1 本地验收和同 SHA 远程 CI 均满足，下一卡为 P7D-01 容器加固。
- 最近远程全绿的 Phase 7 主线候选：`f0ce7d2318ec16da8a70626c0f646d4a47a1227d`；quality Run [33307092918](https://github.com/Mirror18/RAGForge/actions/runs/33307092918) 全绿（5m48s）。容器、Ubuntu、观测、升级和供应链 P2 卡片尚未完成。
- Repository: GitHub `Mirror18/RAGForge`
- Branch: `main`
- 当前权威状态（覆盖本文档中的历史基线行）：功能验证基线为 `bde93ebe9be2b0b9e2614a0cc43baf216285c1b6`，其 GitHub Actions quality Run [`32586867110`](https://github.com/Mirror18/RAGForge/actions/runs/32586867110) 全绿；随后记录提交 `6ec5d9d` 的 quality Run [`32587259456`](https://github.com/Mirror18/RAGForge/actions/runs/32587259456) 亦全绿。两次运行均覆盖静态、契约、SBOM/Grype、Maven、Phase 3–5、评估、安全、性能、Web 门禁；旧 SHA/旧 CI 行仅保留为历史记录。
- 本轮记录：功能合并基线为 `07f973c84fa60dd239ed5c60a443e1edbb801eed`，包含真实 RAG graph stream 与本地并发成本证据；阶段记录提交及其 CI/SBOM/Grype 结果已在下方补记。上方历史远端行保留为上一已知 CI 基线，不能作为本轮提交验证。
- 本轮 CI 已补记：GitHub Actions quality Run [`32579989036`](https://github.com/Mirror18/RAGForge/actions/runs/32579989036) 对提交 `4481bef34cdeed59068b45d03f8a5abbc48bb379` 全绿；SBOM `9477533715`、Grype SARIF `9477541287`、Phase 3 JVM `9477586833`、Phase 4 retrieval `9477586457`、Phase 5 evidence `9477579925` 均已生成。
- 最新功能/阶段证据基线：`462c7a5fded50e4a39e9ee99c26f5254da5c8788`；其 GitHub Actions quality Run [`32580715731`](https://github.com/Mirror18/RAGForge/actions/runs/32580715731) 全绿，SBOM `9477714534`、Grype SARIF `9477725361`、Phase 3 JVM `9477775662`、Phase 4 retrieval `9477775256`、Phase 5 evidence `9477766441` 已生成。后续仅记录性提交不改变该功能验证基线。
- External remote: `origin` configured；最近验证时 `origin/main` 为 `f0ce7d2318ec16da8a70626c0f646d4a47a1227d`。quality Run [33307092918](https://github.com/Mirror18/RAGForge/actions/runs/33307092918) 全绿；SBOM artifact `9730814313`、Grype SARIF `9730823019`、Phase 7 evaluation `9730812337`、contract coverage `9730812078`、Phase 3/4/5 与 quality logs artifacts 均已生成。尚未创建 release。

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

## 7. 业务闭环真实浏览器复核（2026-08-23）

- 真实 Web 路径已闭环：注册/登录 → 创建并切换空间 → Provider/Model Profile/Model Route/Prompt 发布 → Markdown 文件选择与异步摄取 → Parse Report → candidate index 验证并发布 active → LOCAL_ONLY Ollama 引用回答 → citation preview → Run/Step/usage → 同一文档增量同步 → 新 Revision/Index → 第二次引用回答。
- 首次旧前端 30 秒默认超时产生了一次可复现失败，随后服务端成功完成 Run；前端已将本地 Ollama 等待窗口改为 120 秒，重试和最终增量回答均为 `COMPLETED`，失败未被隐藏，详见证据中的 `transient_retry`。
- `answer.usage` 已由服务端从当前空间、当前 Run 的 RAG invocation usage ledger 投影，前端展示 provider-reported input/output/total tokens；成功 Run 的 Run 追踪页展示真实 `correlationId`、sequence 和 Step。
- 浏览器切换到第二空间后读取第一空间 Run 返回 `404 RUN_NOT_FOUND`；未执行任何跨空间 DB seed、手填资源创建或绕过 UI 的状态变更。
- 本节为当前业务闭环验收的权威更正；Phase 6 阶段状态仍为 `completed-with-explicit-waiver`，下一入口仍为 Phase 7。个人 notes 真实文件选择继续保持显式用户手势和不上传云端的安全边界。

## 8. 业务闭环增量（2026-08-23）

- MiMo 已接入现有 Provider Registry：使用 `MIMO` provider type、OpenAI-compatible `/v1/chat/completions` 协议和 `api-key` header；凭据只通过本地 ignored `.env.local` 的 `XIAOMI_API_KEY` 注入，未进入 Git、测试证据、日志或版本化配置。云端仅在前端显式切换到 MiMo Chat 时使用 typed authorization context，Embedding/Rerank 仍保持本地。
- 真实 MiMo RAG E2E 已通过：Run `1e58f763-10a6-4665-a9c2-1445f921b5d2`，correlation `01a02f10-0d9b-73e7-8ce3-74a2ab95d049`，完成 SSE 序列 10、1 条结构化 citation，回答内容与 `space_id` 隔离证据一致；运行未产生服务端 WARN/ERROR。
- 本地成熟模型默认已切换为 Ollama `qwen3.5:9b`，真实 LOCAL_ONLY RAG E2E 已通过：Run `9aa79e04-f5ff-4a35-b055-fc4471ed52de`，correlation `01a02f13-0b19-7636-bb87-ba447596280e`，完成序列 9、1 条结构化 citation，前端显示 `本地 Ollama（LOCAL_ONLY）`。此前 `qwen3.5:0.8b` 的 citation range 不满足投影约束，已保留为风险证据并不再作为默认验收模型。
- RAG prompt 初始化与校验已强化为 `claim_text` 必须是 `answer_text` 的精确连续子串；无效可选字符范围由服务端安全回退为文本定位，伪造 citation UUID 仍严格拒绝。
- 常用本地知识库入口已加入业务流：前端可选择本地 `notes` 文件夹，仅提交 Markdown，并以文件夹相对路径进入当前空间；`.obsidian` 目录、附件和非 Markdown 文件被过滤，服务端继续执行路径遍历、绝对路径和控制字符拒绝。`.env.local` 已配置本机 notes 根路径供本地开发约定使用，但浏览器仍要求用户显式选择文件夹，避免服务端任意读取本机文件。
- 本轮证据和限制见 [`2026-08-23-mimo-notes-business-loop.md`](2026-08-23-mimo-notes-business-loop.md)。实际个人 notes 文件选择/摄取未在自动化浏览器工具中伪造完成，待用户在浏览器文件选择器中执行一次后再补充真实 corpus 摄取证据；个人 notes 不进入 Git、CI、长期 evidence 或云端调用。
## 8.1 核心业务闭环增量（2026-08-24）

- 服务端新增受空间隔离保护的 conversation 历史查询、conversation run 列表和软归档；归档会话保留历史 answer/citation provenance，并拒绝新问题写入。
- 数据源入口扩展为受支持文档文件/文件夹和网页 URL。网页入口复用 revision/artifact 与 ingestion job 流程，并要求显式云端出境授权、域名白名单、公网 DNS、大小和媒体类型检查；RAGFORGE_WEB_SOURCE_ALLOWED_HOSTS 为空时默认拒绝网页抓取。
- 前端问答页新增会话历史、历史 run 查看、同一 conversation 追问和归档入口；业务流新增网页来源入口，并继续由服务端真实状态驱动模型、Prompt、active index 和出境策略。
- 本轮已通过服务端 Java 21 compile、Web TypeScript/build、OpenAPI/contract test；尚未宣称真实浏览器视觉验收和 Phase 7 Linux 交付条件完成。下一动作是用脱敏测试空间执行历史/追问/归档和网页白名单 smoke。

## 9. 管理与前端闭环增量（2026-08-24）

- 平台用户管理已落地：`PLATFORM_ADMIN` 可查看、创建、编辑、停用用户；停用为可审计软删除，禁止自我降权/自我停用，停用用户的登录和已有 session 均失效；普通用户请求用户管理接口返回 403。首个平台管理员仍需通过受控运维流程授予，普通注册账号不会自动升级。
- 知识空间管理已落地：空间管理员可编辑名称/描述、归档空间、查看成员、调整成员角色和移除成员；所有写入使用版本乐观锁，归档不物理删除，最后一个空间管理员不可被降权或移除。成员分配只接受 ACTIVE 用户。
- 前端业务入口已整理为“空间 → 成员/用户 → 云端 Chat 配置 → 知识导入/索引 → 问答追踪”；账号与空间页显示浏览器 IANA 时区，日期时间统一按浏览器时区格式化；`selectedPromptTemplateId` 已在控制台声明并由 TypeScript/构建门禁覆盖。
- Chat 模型默认优先选择云端 MiMo；云端调用仍需要空间显式授权和 typed authorization context，不允许静默云端回退。Embedding/Rerank 继续保持本地能力边界，避免把用户对 Chat 的云端选择扩大成未经授权的全链路出境。
- 代码与证据：`V16__user_lifecycle_management.sql`、`UserAdminController/Service`、`SpaceController/Service`、`PersonalSpaceView.vue`、`format.ts`、OpenAPI v1 增量；`ServerIntegrationTest` 新增用户管理与空间管理安全回归。验证结果为服务端 `ServerIntegrationTest` 7/7、服务端 Java 21 compile、Web format/build、OpenAPI JSON contract test 通过。

## 10. Phase 7 代码反向审计（2026-08-29）

- 当前执行入口为 [`PHASE_7_CHECKLIST.md`](../03-delivery/PHASE_7_CHECKLIST.md) 与 [`PHASE_7_EXECUTION_PLAN.md`](phase-7/PHASE_7_EXECUTION_PLAN.md)。二者以 production code 和可重跑门禁为依据，覆盖并取代此前“Phase 7 只剩部署”的任务判断。
- 已确认的产品断点：注册只产生 `USER` 且没有平台管理员 bootstrap；OpenAPI 的 Provider connection test 没有 Controller 实现；Model Profile 可在没有 verified capabilities 时直接 `PUBLISHED`；generation request 固定 `stream=false`；Git/local connectors 未接 Server/Web；feedback、审计/成本管理视图缺失。
- 已确认的检索断点：BM25 是进程内 `InMemoryBm25CandidateStore`；空间虽绑定 RERANK route，production retrieval 仍使用 `LexicalReranker`；`apps/ai-runtime` 只有包骨架。现状不能被描述为 durable lexical 或真实模型 rerank。
- 已确认的交付断点：Server/Worker 镜像为 UID 10001，但 Web 仍为默认 nginx root；Server/Worker 在 Compose 中没有应用级 healthcheck，三类应用也没有完整 capability/只读写路径/资源限额/digest 证据。
- 本轮门禁：format、architecture、52 contract tests、Compose 静态验证和 secret scan 通过。直接运行 Maven 时 Maven 绑定 JDK 8；显式切到 JDK 21 后 Server 执行 187 tests，20 个 Testcontainers tests 因 Docker daemon 未运行报 error、1 个真实 Ollama 用例 skipped，Worker 未执行。Node/npm 不在当前 PATH，Web 本轮未构建，且项目没有 Web test script。
- 因此当前没有同一候选 SHA 的全量 green、容器 runtime、Ubuntu、升级/回滚或目标镜像 SBOM/Grype 证据；Phase 7 不能完成，也不能创建 release。

## 11. 前端实用性复审（2026-08-29）

- 当前决策：暂停部署工作，先完成前端可用闭环。第 7～9 节记录的是特定测试数据和操作者路径曾成功跑通，不代表普通用户可以独立、持续地使用产品；本节是对“前端已闭环”描述的权威纠偏。
- 身份与协作未闭环：干净数据库没有平台管理员 bootstrap；成员 API 支持 upsert，但 `PersonalSpaceView.vue` 只能修改或移除已有成员，没有选择用户并加入空间的入口，因而“创建用户 → 加入空间 → Editor/Viewer 协作”无法从页面完成。
- Provider 与模型配置未闭环：页面和 `ragSetup.ts` 可以直接创建 ACTIVE/PUBLISHED 配置并写入声明能力，却没有连接测试、verified capability、编辑/停用/轮换或发布闸门；MiMo 初始化还假定本地 Secret 已存在。页面成功提示不能证明模型链路实际可用。
- 来源与索引未完全闭环：文件/网页可以提交，任务和索引管理仍待 P7-WEB-02；P7-CORE-04 已补齐 Git source 配置、只读 clone/discover、全量/增量同步、空间隔离 checkpoint、变更文档任务投递、删除归档和 Web 来源入口。
- Chunk Studio 与 Retrieval Playground 仍是工程调试界面：前者要求手填 `childChunkId`、`contentRef` 和 64 位 hash，且不展示/编辑正文；后者要求手填 index/profile UUID/version，并暴露 synthetic `queryVector` 测试缝。两者没有从文档、检索结果或引用跳转的上下文。
- 问答未闭环：页面把同步生成完成后的 SSE 事件描述为回答增量；引用 preview API 返回 provenance 元数据，但客户端主动丢弃响应，只显示“已鉴权”，不能查看来源正文；历史回答没有完整 citation 恢复，且没有显式“新会话”、反馈、会话重命名/删除入口。
- 管理与导航未闭环：Provider/Profile/Route/Prompt 主要是创建和列表，Run 依赖手填 ID，审计/成本/保留没有管理页面；列表普遍固定 `limit=100` 或 `slice(0, 5)` 且忽略 cursor；应用没有 URL Router、可恢复页面状态或 Web unit/component/E2E 测试。
- 产品判定：当前 Web 是可验证后端能力的工程控制台，不是可交付给普通用户的实用产品。Phase 7 下一工作入口调整为：协作与首次设置 → 来源/任务/索引维护 → 可核验问答 → 调试与管理工具 → Web 自动化；这些 P0/P1 完成后才恢复容器和 Ubuntu 交付。

## 12. P7-B 首次设置与协作进度（2026-08-29）

- `P7-WEB-01` 已完成：新增空间级按精确邮箱添加成员 API 与页面表单。只有当前空间管理员可以操作；服务端只匹配 ACTIVE 注册用户，已存在成员返回冲突，不开放全站用户搜索或模糊账号枚举。
- 审计事件为 `space.member.added.v1`，payload 只记录 `spaceId`、`userId` 和角色，不记录邮箱或其他账号内容；所有写入继续经过 CSRF、Idempotency-Key、`space_id` 和服务端角色校验。
- 验证：`SpaceServiceTest` 3/3；完整 `ServerIntegrationTest` 8/8；扩展后的成员定向 Testcontainers 用例 1/1；OpenAPI contract 52/52；format、architecture、secret scan、Web TypeScript 与 Vite build 通过。
- 当时 P7-B 仍未完成：首个平台管理员 bootstrap 与 Provider connection test/verified publish gate 是后续任务；本轮未启动 RAGForge Compose 或执行部署。Bootstrap 的后续完成状态见第 13 节。

## 13. P7-CORE-01 平台首次设置闭环（2026-08-29）

- 干净数据库现在可从登录页完成首个平台管理员初始化。入口只有在不存在 ACTIVE `PLATFORM_ADMIN` 且服务端显式配置 `RAGFORGE_BOOTSTRAP_ADMIN_TOKEN` 时可用；Token 最少 32 字符，通过专用 Header 提交，不进入浏览器存储、API 响应或审计 payload。
- 初始化可创建新管理员，也可按精确邮箱提升既有 ACTIVE 用户并替换密码；停用用户拒绝提升。普通注册路径仍固定创建 `USER`，不存在“抢注首个账户自动提权”。
- PostgreSQL transaction advisory lock 把检查与创建置于同一事务内；并发请求最多一个返回 `201`，完成后的请求固定返回冲突。审计事件 `platform.admin.bootstrapped.v1` 仅记录 `userId` 与 `mode`，无邮箱、密码或 Token。
- 验证：`BootstrapAdminPropertiesTest` 3/3；完整 `ServerIntegrationTest` 12/12，其中覆盖无效 Token、一次性完成、既有 ACTIVE 用户提升/旧密码失效、停用用户拒绝、审计脱敏和双请求并发；Web TypeScript 与 Vite build 通过。本轮没有启动 RAGForge Compose、执行部署或创建 release。
- 下一项仍为 `P7-CORE-02`：先收敛 Provider ownership/权限，再完成 connection test、verified capabilities 持久化与 Profile 发布闸门；P7-B 在此之前保持 `pending`。

## 14. P7-CORE-02 Provider 验证与发布闸门（2026-08-29）

- Provider connection 继续使用现有 space-scoped 外键，未擅自启用 `space_id = NULL` 的全局共享语义。只有同时属于目标空间的平台管理员可登记和实测 connection；空间管理员负责 Profile/Route，Editor/Viewer 不再拥有 Provider 配置写权限。
- `POST /provider-connections/{id}/test` 已有 Controller 与 Web 入口。CHAT/EMBEDDING 复用 production adapter 和固定合成样本；云端测试逐次确认。结果与审计在同一事务内持久化，只含 ID、用途、成功/失败、verified capabilities、embedding dimension、错误分类、retryable 和耗时。
- Profile 发布以同 connection、model、purpose 的最新一次测试为准；失败复测、能力超报或 embedding 维度不一致均返回 422。没有 RERANK adapter 时返回 `UNSUPPORTED_CAPABILITY`，因此历史“一键本地 RAG”不能再靠声明值伪造 RERANK 可用，后续由 `P7-CORE-05` 闭环。
- 安全复核：探测前重新校验 HTTP(S) URI 与 DNS/IP 分类；LOCAL 只允许本地/私网，CLOUD 只允许 HTTPS 公网，并且没有本次显式云确认时在发起网络请求前返回 403。仍需部署层 egress policy 抵御 DNS TOCTOU/rebinding，记录为 `R-053`。
- 验证基线：Provider/Model/Binding 三组集成测试 14/14 通过，包含真实 loopback HTTP 合成探测、权限、缺失测试拒绝、失败复测阻断、恢复后发布、审计/存储脱敏与云端未确认拒绝；Java 21 test-compile、OpenAPI contract 52/52、Web TypeScript/Vite build 通过。本轮未部署、未启用任何空间云出境、未调用真实云 Provider。

## 15. P7-CORE-03 真实生成 Streaming（2026-08-29）

- Production Provider adapter 已实现 Ollama NDJSON 与 OpenAI-compatible/MiMo SSE。CHAT connection test 也改为真实 stream，只有实际完成流协议才验证 `CHAT`、`STREAMING` 及可用的 `USAGE_REPORTING`。
- `ProviderBackedGenerationPort` 不向下游传递 raw frame 或结构化 JSON chunk，只增量解码根级 `answer_text`；总输出限制 1 MB，SSE delta 再切成最大 4096 字符事件。完整 JSON、claims、evidence UUID allow-list 和引用投影继续在终态强校验。
- 每次 answer 在生成前获得稳定 `answerId`。delta 进入既有 durable run-event store，因此 live SSE 与 `Last-Event-ID` replay 读取同一序列；终态不重复发送完整正文。失败、拒答或取消时 Web 清空暂态正文，不能把未验证 delta 留作答案。
- Cancel 先把 Run/event stream 标为 `CANCELLED`，阻止后续 delta，再取消同一生成 token 和 HTTP body。生成实例还订阅共享 run-event fan-out，cancel 落到其他 Server 实例时也能关闭上游流。
- 验证：Provider HTTP、generation bridge、RAG service、Answer API 和 Provider loopback integration 共 63/63 通过，覆盖两类流协议、分块结构化 JSON 解码、durable delta 不重复、同 token cancel、远端 fan-out cancellation event、上游 body 关闭和真实 loopback CHAT streaming probe；另有事件持久化/多实例 fan-out 回归 6/6 通过。本轮未部署、未调用真实 Ollama/MiMo，真实 TTFT/吞吐需在发布候选复测。

## 16. Phase 7 P0/P1 对齐与 P2 入口复核（2026-08-30）

- 功能候选 `f695936594834f8a870fa95dca5ff0c6634441a1` 已包含 P7C-01~08、P7Q-01~06，以及恢复卡 P7C-05R。恢复卡修复 test profile 中 fake/production `AI_RUNTIME` adapter 重复注册；默认 profile 仍注册真实 AI Runtime adapter，registry 重复检测未被弱化。
- 本地同候选验证：preflight 6/6；JDK 21 + Docker Maven reactor 307 tests（306 passed、0 failed、0 errors、1 skipped）；contract 52/52；OpenAPI/Controller 102/102；Web Vitest 10/10、Playwright 10/10、TypeScript/Vite build；128-case RAG gate；format、architecture、Markdown links、secret scan、dependency inventory 与 Compose static validation 全绿。
- 评估证据为公共 synthetic fixture，candidate retrieval/generation 指标均为 1.0，cross-space、Evidence 外引用、prompt-injection tool 和 unauthorized cloud 违规均为 0；这不替代真实模型质量、容量或人工/red-team 结论，R-005/R-012 的既有豁免与 R-054 残余风险继续保留。
- P2 入口仍为 `BLOCKED`：当前候选尚未推送，因而没有同 SHA GitHub Actions Linux 结果。只有推送后远程全绿，才能派发 P7D-01；本次没有创建 release、接受许可证、执行生产迁移或开启云出境。
- 结构化汇总见 [`phase7-p2-entry-local-gates.v1.json`](../../tests/evidence/phase7-p2-entry-local-gates.v1.json)，详细 RAG 证据见 [`phase7-evaluation-f695936.v1.json`](../../tests/evidence/phase7-evaluation-f695936.v1.json)。

## 17. P7Q-05R 远程 RAG 门禁恢复（2026-08-30）

- 首次推送 `4ade90f` 后，quality Run [33306553953](https://github.com/Mirror18/RAGForge/actions/runs/33306553953) 在 RAG evaluation step 失败：`actions/checkout@v4` 默认浅克隆不含 `github.event.before=9fdd94e`，`git diff` 返回 `fatal: bad object`。其他已执行静态、契约和 coverage 步骤均通过；后续步骤因 job fail-fast 跳过。
- P7Q-05R 在 checkout 设置 `fetch-depth: 0`，确保 push before/head 与 pull request base/head 可解析；RAG gate 对无效 ref 仍 fail-closed，不改为 skipped。浅克隆失败与 unshallow 成功均已复现，RAG gate 单测 4/4、format、secret scan 通过。
- 修复实现提交 `56bfcef18c01c441d3f1a1ee0e7e6f5b650ef25d`，集成提交 `2b961f2832c83c247914a74276e89fd71f79c1eb`。在重推完成前，P2 当时继续阻塞；最终解除记录见下一条。
- 重推后的主线 `f0ce7d2318ec16da8a70626c0f646d4a47a1227d` 已由 quality Run [33307092918](https://github.com/Mirror18/RAGForge/actions/runs/33307092918) 全绿验证，耗时 5m48s；RAG、完整 Maven、SBOM/Grype、Phase 2–5、安全和 Web 门禁全部通过。P2 入口阻塞解除。
