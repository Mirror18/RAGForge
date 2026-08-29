# 风险登记表

## 1. 评分

Probability（P）与 Impact（I）各 1–5，Score = P × I。15–25 为高，8–14 为中，1–7 为低。状态：OPEN、MITIGATING、ACCEPTED、CLOSED。

| ID | 风险 | P | I | 分数 | 缓解/验证 | Owner | 状态 |
|---|---|---:|---:|---:|---|---|---|
| R-001 | 本机 8GB VRAM 无法支撑并发生成 | 5 | 3 | 15 | 本地仅功能验收；云/Mock 做 20 并发；Provider 可替换 | Architecture | MITIGATING |
| R-002 | Obsidian YAML/标题/wikilink/路径解析失真 | 4 | 4 | 16 | 自有 Connector/contract corpus；Chunk Studio；Windows/Linux 测试 | Ingestion | OPEN |
| R-003 | 跨空间数据泄漏 | 3 | 5 | 15 | space_id 不变量、RBAC、Qdrant filter、零容忍安全测试 | Security | OPEN |
| R-004 | 云端 fallback 未授权出境 | 3 | 5 | 15 | 默认 local-only；route policy；provider spy tests；审计 | Security | OPEN |
| R-005 | 低质量引用导致“看似可信”答案 | 4 | 5 | 20 | server citation validation、evidence provenance、120-case eval；Phase 6 人工/red-team 门槛按用户明确决定豁免，后续 RAG 变更必须重新复核 | RAG | ACCEPTED |
| R-006 | Parser/OCR 恶意文件造成 RCE/DoS | 3 | 5 | 15 | quarantine、AV、sandbox、limits、malicious corpus | Security | OPEN |
| R-007 | 消息重投造成重复索引或成本 | 4 | 4 | 16 | Outbox、幂等 key、attempt/ledger dedupe、fault tests | Platform | OPEN |
| R-008 | 半构建 Qdrant index 被发布 | 3 | 5 | 15 | candidate state、validation、atomic active pointer、rollback；Phase 4 evidence 已覆盖 | Retrieval | CLOSED |
| R-009 | 过早微服务化拖慢学习和交付 | 3 | 4 | 12 | ADR-0001、架构测试、证据驱动拆分 | Architecture | MITIGATING |
| R-010 | 第三方许可证影响未来公开/商业使用 | 3 | 5 | 15 | license gate、SBOM、reuse register、禁止限制源码复制 | Compliance | MITIGATING |
| R-011 | 框架版本过新导致生态不兼容 | 3 | 3 | 9 | Boot 3.5/Spring AI 1.1 基线；编码前 compatibility spike | Platform | MITIGATING |
| R-012 | 评估集过小或偏向个人笔记 | 4 | 4 | 16 | 多格式/无答案/冲突/安全 120 cases；Phase 6 人工/red-team 门槛按用户明确决定豁免，后续评估扩展必须重新复核 | Quality | ACCEPTED |
| R-013 | Langfuse 完整自建超出本机资源 | 4 | 2 | 8 | 独立 llmops profile；核心 OTel 不依赖它 | Operations | MITIGATING |
| R-014 | 真实个人笔记误进 Git/CI/云模型 | 3 | 5 | 15 | private-local 分类、只读 mount、ignore/scan、默认禁出境 | Security | OPEN |
| R-015 | 组件较多导致 Compose 运维复杂 | 4 | 3 | 12 | core/observability/llmops profiles、健康检查、Runbooks | Operations | OPEN |
| R-016 | 备份存在但无法一致恢复引用 | 3 | 5 | 15 | manifest、以 PG 为真相、Qdrant 重建、季度恢复演练 | Operations | OPEN |
| R-017 | Windows Docker Desktop npipe 与旧 Testcontainers 版本兼容性 | 4 | 3 | 12 | 升级 Testcontainers `1.21.4`、显式管理容器生命周期；全量 Maven 8 tests 通过 | Platform | CLOSED |
| R-018 | GitHub remote 未配置导致 CI 证据缺失 | 3 | 4 | 12 | 已配置 `Mirror18/RAGForge`；Run `31616214088` 成功并保存 SBOM artifact `9149315317`、Grype 结果 | Compliance / Platform | CLOSED |
| R-019 | 完整 service token 生命周期尚未进入 Phase 1 | 3 | 4 | 12 | 当前浏览器只走 HttpOnly Session Cookie + CSRF；Phase 2 实现 hash/scope/expiry/revoke/last-used 后关闭 | Security | ACCEPTED |
| R-020 | MinIO 等运行时镜像使用 tag，许可证和生产 digest 尚未锁定 | 2 | 5 | 10 | Phase 1 依赖登记；发布前 SBOM、许可证复核、immutable digest 和镜像扫描 | Compliance / Operations | MITIGATING |
| R-021 | Run retry context 仅保存在进程内，重启后历史失败 Run 无法继续 retry | 3 | 4 | 12 | Phase 2 已验证同进程 timeout/retry；Phase 3/6 设计持久化 retry command/context 和恢复演练 | Platform | OPEN |
| R-022 | 真实 OCR runtime 不可用导致扫描 PDF 质量门禁无法闭环 | 4 | 4 | 16 | Tesseract 受限子进程、PDFBox 渲染、输入/页数/输出/超时上限；Windows 与 Ubuntu CI 真实 2/2 样本 | Ingestion / Operations | CLOSED |
| R-023 | BM25 当前为进程内确定性实现，重启后 lexical index 需重建 | 3 | 4 | 12 | Phase 4 明确 provider seam 与 space/index scope；Phase 5/6 选择 durable lexical provider 前不得宣称重启持久化 | Retrieval | OPEN |
| R-024 | 1M Qdrant 证据使用 8 维合成向量，不能直接外推生产 embedding 维度和混合并发 | 3 | 4 | 12 | Qdrant `v1.11.5` 1M 真实 filter probe p95 1101.3382ms；生产 embedding 维度、并发和索引重建需 Phase 6 复测 | Performance / Retrieval | MITIGATING |

## 2. 维护规则

- 每个 Phase 进入和结束时复审概率、影响和证据。
- 高风险必须有负责人、最近动作和验证日期；不能无限期保留抽象“关注”。
- 风险关闭需链接测试、演练、ADR 或运行数据。
- 新 connector/provider/tool、权限粒度或部署方式自动触发威胁模型和风险表复审。

## 3. Phase 0 基准新增风险（2026-08-12）

| ID | 风险 | P | I | 分数 | 缓解/验证 | Owner | 状态 |
|---|---|---:|---:|---:|---|---|---|
| P0-BENCH-001 | RAGFlow 全局 dataset 没有 query-level `space_id` 过滤，基准中 q-013/q-014/q-032 暴露跨空间来源 | 4 | 5 | 20 | Phase 1 将 `space_id` 设为 query/mutation 强制字段，增加允许来源/禁止来源/拒答安全回归集；证据：[`PHASE_0_BENCHMARK_RESULTS.md`](phase-0/PHASE_0_BENCHMARK_RESULTS.md) | Security / Retrieval | OPEN |
| P0-BENCH-002 | AnythingLLM 对 image-only PDF 生成伪 OCR，且 q-032 泄漏 `BETA-COMET-29` | 4 | 5 | 20 | parser/OCR 必须暴露状态、证据和失败原因；生成前后执行 provenance/forbidden-source 校验；纳入 Phase 1 安全验收 | Ingestion / Security | OPEN |
| P0-BENCH-003 | AnythingLLM 重复 basename 导致 36 份中只有 35 份入库，同名来源也使 citation 无法安全区分 | 4 | 4 | 16 | source identity 使用稳定 source/document ID，不以 basename 作为唯一键；增加 duplicate-name contract test | Ingestion / Retrieval | OPEN |
| P0-BENCH-004 | RAGFlow 重启约 37 秒才 ready，并出现 `Load term.freq FAIL!`；Elasticsearch 达服务限制约 69% | 3 | 4 | 12 | 建立 readiness probe、启动超时预算、ES 资源基线和告警；重启恢复作为 Phase 1 integration gate | Operations | MITIGATING |

## 4. Phase 1 复审（2026-08-12）

- `R-003` 仍为 OPEN：Phase 1 已验证身份/空间 membership 的 no-leak，但 Qdrant payload、对象 URI、缓存 key 和未来内容查询尚未实现，不能关闭跨空间总风险。
- `R-017` 已由 `545d75d`/`ffdb0d5` 关闭；Testcontainers 真实容器集成测试已通过。
- `R-018` 已由 GitHub Actions Run `31616214088` 关闭；SBOM artifact `9149315317` 未过期，Grype 以 High 为失败阈值且 job 成功。
- 未发现新的 P0/P1 代码缺陷；`R-019` 的接受范围仅限 Phase 1 浏览器 Session 骨架，不授权新增 bearer/service-token 用途。

## 5. Phase 2 复审（2026-08-13）

- `R-004` 已关闭：Provider adapter、Space Binding、Run binding enforcement 和出境隔离 5/5 共同证明 local-only 默认、cloud 授权显式、跨空间/未授权请求在 provider 调用前拒绝，且 local 失败不静默 fallback 到 cloud；证据见 [`test_phase2_egress_isolation.py`](../../tests/security/test_phase2_egress_isolation.py) 和 [`SpaceBindingApiIntegrationTest.java`](../../apps/server/src/test/java/com/ragforge/server/provider/SpaceBindingApiIntegrationTest.java)。
- `R-007` 在 Phase 2 的 Run usage 范围内关闭：取消不写 usage，超时重试产生新 Run/Invocation，成功重试只产生一条 usage ledger，且 provider-reported usage 去重测试通过；摄取消息重投、Outbox/DLQ 和索引成本仍留给 Phase 3，不提前扩展关闭范围。
- 新增 `R-021`：Run retry context 当前保存在进程内，进程重启后历史失败 Run 无法继续 retry。P=3、I=4、Score=12，Platform，OPEN；Phase 3/6 评估持久化 retry command/context 和恢复演练。
- `R-003` 仍为 OPEN：Phase 2 已覆盖 Provider/Run/Binding 的空间隔离，但 Qdrant payload、对象 URI、缓存 key 和未来内容查询尚未实现，不能关闭跨空间总风险。
- `R-001` 仍为 MITIGATING：本地 Ollama 只做单链路功能验收，20 链路并发由 Mock 云测试覆盖，不代表本机模型可承载并发生成。

## 6. Phase 3 复审（2026-08-13）

- `R-003` 仍为 OPEN：Phase 3 已把 `space_id` 贯穿 source/revision/artifact/object key，并通过跨空间拒绝测试；Qdrant、chunk 和内容查询尚未实现，不能关闭全局跨空间风险。
- `R-006` 仍为 OPEN：Phase 3 已验证 MIME/大小/路径/符号链接/内容寻址和 OCR 失败边界，但生产 quarantine、AV、sandbox、压缩炸弹专门 corpus 尚未完成。
- `R-007` 进入 MITIGATING：Outbox、RabbitMQ retry/DLQ、PostgreSQL 幂等唯一约束和 20 次并发副作用测试已通过；真实完整 ingestion side-effect handler 与索引成本仍留给后续阶段。
- `R-010` 继续 MITIGATING：PDFBox 2.0.30、POI 5.4.0、MinIO SDK 8.6.0、OkHttp JVM 5.1.0、Tesseract/Leptonica 运行时已记录于 [`DEPENDENCY_AND_LICENSE_EVIDENCE.md`](phase-3/DEPENDENCY_AND_LICENSE_EVIDENCE.md)；Run [31706823033](https://github.com/Mirror18/RAGForge/actions/runs/31706823033) 的 Syft/Grype 通过，正式发布仍必须复核传递依赖、训练数据和目标发行包许可证。
- `R-020` 继续 MITIGATING：MinIO 测试镜像使用固定 release tag 但尚未锁定生产 digest；发布前必须完成 digest、SBOM、许可证和镜像扫描。
- `R-022` 已关闭：`TesseractOcrEngineTest` 真实执行两份无文本层合成 PDF，Windows `5.4.0.20240606` 与 Ubuntu CI `5.3.4-1build5` 均 2/2 成功；Parse Report 具备 artifact、页码、版本、触发原因与 `COMPLETED` 审计状态，证据见 [`phase3-ocr-runtime-summary.json`](../../tests/evidence/phase3-ocr-runtime-summary.json)。

## 7. Phase 4 复审（2026-08-21）

- `R-003` 仍为 OPEN，但范围已收窄：Phase 4 已用 PostgreSQL/Qdrant/Valkey 和 targeted Maven 17/17 覆盖 chunk、override、embedding cache、索引指针和检索过滤；Phase 5 的 citation validator、只读工具和跨空间回答仍未完成，不能关闭全局风险。
- `R-008` 已关闭：candidate 必须经 VALIDATING/READY 和完整校验后才能 ACTIVE；失败验证不污染 active pointer；第二索引切换记录 previous pointer，旧索引至少保留 24 小时。证据见 [`phase4-isolation-and-override.json`](../../tests/evidence/phase4-isolation-and-override.json)。
- `R-012` 继续 OPEN：Phase 4 30 问固定切片达到 Recall/MRR 阈值，但全量 120+ 评估、人工复核和生成质量仍属于 Phase 5/6。
- 新增 `R-023`：BM25 provider 当前为进程内确定性实现，重启后的 durable lexical index 尚未选型；在该风险关闭前不宣称重启后 lexical 数据持久化。
- 新增 `R-024`：Qdrant 1M 真实本地探针使用 8 维合成向量和 100 次查询，Recall@10 `1.0`、p95 `1101.3382 ms`；该结果满足当前阶段退出条件，但不替代生产 embedding 维度、并发和混合索引容量复测。

## 8. Phase 5 复审（2026-08-22）

- `R-025` 已关闭（阶段范围）：用户接受 ADR-0010 方案 A；既有 provider connection、空间绑定 revision/artifact material service、显式 opt-in graph 和本地 Ollama `LOCAL_ONLY` 真实 RAG E2E 已通过，证据见 [`phase5-real-ollama-rag-e2e.v1.json`](../../tests/evidence/phase5-real-ollama-rag-e2e.v1.json)。云 route/生产凭据仍不在授权范围，继续由 ADR-0005 和 fail-closed 约束。
- `R-026` 阶段范围已关闭：真实 server 重启后健康检查和 durable replay/cancel 证据通过，见 [`phase5-run-events-restart-cancel.v1.json`](../../tests/evidence/phase5-run-events-restart-cancel.v1.json)。过期事件清理和多实例 live fan-out 转入 Phase 6，因此不宣称运维风险全局关闭。
- `R-003` 仍为 OPEN（全局范围）：Phase 5 的真实单空间 E2E、安全测试和审计均未发现泄漏，但仍需 Phase 6 多租户压力、云 route 和更大规模数据复测后再关闭全局风险。
- `R-005` 继续 MITIGATING：合成 12 cases 的 candidate citation precision、faithfulness、abstention accuracy 均为 `1.0`，但样本规模和 judge 均为 deterministic fixture；Phase 6 扩展 120+ 样本、人工复核和 prompt-injection/red-team。
- `R-012` 继续 OPEN：Phase 5 的性能文件只提供合成 E2E/TTFT 及 retrieval/generation 代理；生产 embedding 维度、并发、模型上下文和成本仍需真实环境复测。
- `R-027` 新增：同步非流式 Ollama 适配器不暴露首 token 时间，真实 E2E 只能记录 retrieval/generation/E2E，TTFT 明确为 `NOT_MEASURED`。P=2、I=3、Score=6，Performance，MITIGATING；Phase 6 流式协议和 TTFT 观测补齐前不得声称真实 TTFT 达标。
- `R-028` 新增：本地 SBOM 门禁因环境缺少 `syft/trivy` 未能执行，当前依赖旧 CI 证据且需新提交 CI 复核。P=2、I=4、Score=8，Compliance，MITIGATING；推送后以 GitHub Actions SBOM/Grype artifact 作为本提交证据。

## 9. Phase 6 复审（2026-08-22）

- `R-005` 继续 MITIGATING：Phase 6 已形成 128 个版本化公共合成评估用例，确定性 candidate 指标为 1.0；人工/red-team review manifest 尚未完成，不能关闭引用、拒答和冲突场景的质量风险。
- `R-012` 继续 OPEN：评估规模门槛已达到 128 cases，但人工复核和真实模型质量证据仍缺失；不得以 deterministic fixture 指标替代人工 review。
- `R-024` 已关闭（Phase 6 容量门槛范围）：a2 隔离 Compose 使用真实 Ollama 768 维、1,000,000 synthetic child chunks、4-space filter、20 并发混合负载完成；Recall@10 `0.995`、p95 `119.8761ms`、错误率 `0`，证据见 [`phase6-capacity-retrieval-a2.v1.json`](../../tests/evidence/phase6-capacity-retrieval-a2.v1.json)。向量值为 live dimension 下的公共合成值，生产语义质量和成本仍不由该证据承诺。
- V14 运维复审：answer/event retention 删除已改为显式 `space_id` 参数和按空间调度；隔离 scheduler 过期 event 1 → 0，跨空间 answer purge 回归通过，证据见 [`phase6-operations-runtime.v1.json`](../../tests/evidence/phase6-operations-runtime.v1.json) 与 [`Phase5PersistenceIntegrationTest`](../../apps/server/src/test/java/com/ragforge/server/run/Phase5PersistenceIntegrationTest.java)。
- `R-029` 已关闭（Phase 6 在线性能门槛范围）：隔离 server `ragforge-p6-online` 通过正式 register/login session 创建 synthetic LOCAL_ONLY Ollama run，100 次 health API 与 100 次 SSE first-event 均成功；non-AI p95 `28.7487ms`、SSE first-event p95 `35.9285ms`，证据见 [`phase6-capacity-online.v1.json`](../../tests/evidence/phase6-capacity-online.v1.json)。TTFT 仍单独排除。
- `R-027` 继续 MITIGATING：真实 Ollama RAG E2E 和 SSE first-event 门槛均已有证据，但当前同步适配器仍不测 TTFT，不能将 first-event p95 解释为 TTFT。
- `R-027` 补充证据：[`phase6-real-ollama-stream-metrics.v1.json`](../../tests/evidence/phase6-real-ollama-stream-metrics.v1.json) 通过 loopback `LOCAL_ONLY` standalone stream probe 实测 TTFT `9130.6742ms`、provider total `11456.3744ms`、wall `11475.2584ms`、`19.6176 tokens/s` 和 provider usage `35/46/81`；该探针不改变同步 RAG graph，故集成路径 TTFT 仍未测量，风险保持 MITIGATING。
- `R-027` 更新：[`phase6-real-ollama-rag-graph-stream.v1.json`](../../tests/evidence/phase6-real-ollama-rag-graph-stream.v1.json) 已在真实 revision/artifact-backed RAG graph 到 loopback Ollama stream 边界测得 graph-to-first-token `1675.9884ms`、provider TTFT `1560.7450ms`、provider total `4847.3558ms`、wall `4854.6037ms`；生产同步 `GenerationPort` 仍未暴露 streaming，因此风险保持 MITIGATING，不能把边界证据写成生产 API 能力。
- 新增 `R-030`：本地 Ollama 2 并发成本 probe 已完成 4/4 成功，TTFT p50/p95 `1482.8559/2688.2120ms`、wall p50/p95 `2762.1378/4013.6133ms`、usage `144/108/252`、估算成本 `0 USD`；该结果只反映 loopback 本地观测，不代表云端商业价格、生产资源容量或真实模型质量。P=3、I=3、Score=9，Performance / Platform，MITIGATING。
- `R-028` 已关闭（本提交范围）：本地 Syft/跟踪内容 Grype 已通过，最新 GitHub Actions quality [`32577917976`](https://github.com/Mirror18/RAGForge/actions/runs/32577917976) 全绿；阶段 SBOM artifact `9477027172`、Grype SARIF artifact `9477036384` 可追溯。发布前仍需按目标镜像/digest 重跑发布级扫描。
- 观测 profile、fault drill、隔离恢复和 23/23 安全专项均已取得证据，但 `R-003` 全局风险仍 OPEN，需容量压力与人工安全复核完成后再评审关闭。
- `R-031` 已关闭（多实例 run-event fan-out）：ADR-0011 已于 2026-08-22 由用户接受；两个独立 Spring server context + 共享隔离 PostgreSQL/Valkey 的跨实例、同/跨空间隔离、提交后发布、回滚不泄漏、重复/乱序补洞、Last-Event-ID durable replay 和 listener shutdown 均通过，证据见 [`phase6-multi-instance-run-event-fanout.v1.json`](../../tests/evidence/phase6-multi-instance-run-event-fanout.v1.json)。该结论不外推生产容量、云端部署或跨区域语义。
- `R-032` 新增：前端业务闭环代码与真实 Server/Worker API 已通过构建、契约和本地运行证据，但浏览器登录后的视觉/交互验收尚未完成。P=2、I=3、Score=6，Product / Web，MITIGATING；完成本地测试账号登录、配置初始化、上传轮询和带引用回答的浏览器验收后再关闭。
- 2026-08-23 复审：取消执行竞态与 rollback-only 事务失败已在独立 worker 分支修复并合并，server `210`、根工程 `238` 测试均为 0 failures/0 errors/1 skipped；quality run `32586867110`（功能基线）和记录提交后的 `32587259456` 均全绿。该复审当时仍保留 `R-005`、`R-012` 和 `P6-EVAL-04` 的人工/red-team 评审缺口，不得以自动化结果代签；后续治理例外见下一条记录。
- 2026-08-23 治理复审：项目用户明确批准豁免 P6-EVAL-04 的至少 2 名人审 + 1 名红队评审签名门槛。R-005、R-012 转为 `ACCEPTED` 而非 `CLOSED`；manifest 记录 `PASS_WITH_EXPLICIT_WAIVER`，保留空签名和未执行人工复核事实。后续模型、prompt、retrieval 或安全策略变更必须重新开启独立人工/red-team 复核。

## 10. MiMo 与本地 notes 增量复审（2026-08-23）

- 新增 `R-033`：MiMo 凭据已按用户要求写入本地 ignored `.env.local`，但密钥曾在对话中暴露，存在凭据泄露风险。P=4、I=5、Score=20，Security / Operations，MITIGATING；不进入 Git、日志、证据或 CI，使用后应尽快在 MiMo 控制台轮换/撤销；生产环境改用正式 Secret 管理。
- 新增 `R-034`：浏览器安全模型要求用户显式选择本地 `notes` 文件夹，当前自动化浏览器工具未执行真实文件选择和个人 notes 摄取。P=3、I=4、Score=12，Product / Web，MITIGATING；保留文件夹入口、`.obsidian` 过滤、相对路径安全校验，待用户选择一次真实脱敏目录后补充 ingestion E2E 证据。
- 新增 `R-035`：Ollama `qwen3.5:0.8b` 在真实回答中产生不满足 citation range 投影的结构化结果。P=3、I=4、Score=12，RAG / Platform，MITIGATING；默认使用已通过真实 RAG E2E 的 `qwen3.5:9b`，服务端保留严格 citation 校验和安全范围回退，不把小模型作为默认验收基线。

- `R-032` 已关闭（本轮业务闭环范围）：真实浏览器完成注册/登录、建空间、配置发布、摄取、索引、引用问答、引用预览、Run/Step/usage 和增量同步；证据见 [`business-loop-e2e.v1.json`](../../tests/evidence/business-loop-e2e.v1.json)。这不代表 Phase 7 发布验收或观测 Dashboard 视觉验收完成。
- `R-034` 保持 MITIGATING：`D:\\project\\learning\\notes` 已作为本地配置约定，浏览器入口、Markdown/.obsidian 过滤和相对路径安全已验证；本轮为避免读取个人内容，未执行真实个人 notes 摄取。用户显式选择脱敏目录后再补 corpus 证据。

## 10.1 核心业务闭环增量复审（2026-08-24）

- 新增 R-038：网页知识源依赖服务端域名白名单和云端出境授权，默认空白名单会拒绝抓取；若部署者误配白名单或目标站点变化，网页摄取可能失败。P=3、I=4、Score=12，Security / Ingestion，MITIGATING；保持 allowlist、DNS 公网地址、无自动重定向、响应大小/媒体类型限制，并补充真实白名单 smoke。
- 新增 R-039：历史 answer projection 依赖当前 durable answer persistence；尚未完成浏览器视觉验收，部分历史 run 可能只显示运行状态而没有 answer projection。P=2、I=3、Score=6，Product / Web，MITIGATING；前端明确显示不可读取状态，不伪造回答内容，后续用真实测试空间补验收。

## 11. 用户与空间管理、前端可用性增量复审（2026-08-24）

- 新增 `R-036`：平台管理员首个授予仍依赖受控运维流程，若部署未完成 bootstrap，普通注册用户无法看到平台用户管理入口。P=3、I=3、Score=9，Operations / IAM，MITIGATING；不自动提升首个注册用户，部署时按受控流程授予 `PLATFORM_ADMIN`，并在后续补充 bootstrap runbook。
- 新增 `R-037`：本轮完成 API/构建/集成安全回归，但尚未替代真实浏览器对用户管理、空间归档和时区显示的视觉验收。P=2、I=3、Score=6，Product / Web，MITIGATING；已增加清晰入口、错误提示和浏览器时区显示，下一轮用真实测试账号执行 Web smoke。
- `R-033` 继续保持 MITIGATING：MiMo 凭据只存在本地 ignored 配置，且曾在对话中暴露；使用后仍应轮换，生产必须使用 Secret 管理。
- `R-004` 的云端出境边界未放宽：Chat 默认优先 MiMo 仅是前端选择策略，仍必须经过空间授权和 typed authorization context；Embedding/Rerank 保持本地，不允许静默 fallback。

## 12. Phase 7 进入复审（2026-08-29）

- 新增 `R-040`：当前候选没有可复现的本地全量 green 或同 SHA 远程 Linux CI。系统 `java` 为 21 但 Maven 绑定 JDK 8；显式切到 21 后又因 Docker daemon 不可用导致 20 个 Testcontainers error；Node/npm 不在 PATH。P=4、I=5、Score=20，Platform / Delivery，OPEN；先实现统一 preflight，再在固定 JDK/Node/Docker 环境生成完整证据。
- 新增 `R-041`：Server/Worker 镜像已使用 UID 10001，但 Web 仍为默认 nginx root；应用镜像仍使用 `:local`，Server/Worker Compose health、只读文件系统、capability 收敛、资源限额和 digest 尚未形成发布证据。P=3、I=5、Score=15，Security / Compliance / Operations，OPEN；发布前完成三类应用一致加固和目标镜像 SBOM/Grype。
- 新增 `R-042`：Phase 7 尚未证明从上一兼容版本升级并在定义窗口内回滚；Flyway 为向前迁移，应用回滚必须受 schema 兼容矩阵约束。P=3、I=5、Score=15，Operations / Data，OPEN；使用合成数据执行备份、升级、业务校验和兼容回滚演练，禁止对生产数据库试跑。
- `R-005` 与 `R-012` 保持 `ACCEPTED`：本轮 retrieval/answer 相关性逻辑有变更，已增加 material-backed 与误拒回归测试，但这不自动撤销 Phase 6 的人工/red-team 豁免事实；Phase 7 发布候选形成后仍需按清单重新评估是否触发独立复核。
- 新增 `R-043`：干净数据库无法安全产生首个平台管理员；注册固定创建 `USER`，平台用户管理又要求已有 `PLATFORM_ADMIN`。P=5、I=4、Score=20，IAM / Operations，OPEN；实现一次性、可审计、fail-closed 的 bootstrap，禁止“首个注册用户自动提权”。
- 新增 `R-044`：Provider connection 当前是 space-scoped 且 Editor 可写，与平台管理员登记/空间管理员选择的需求不一致；connection test 只存在于 OpenAPI，Model Profile 又可在 verified capabilities 为空时直接 `PUBLISHED`。P=4、I=5、Score=20，Provider / IAM / RAG，OPEN；先收敛 ownership/权限，再实现脱敏能力探测、结果持久化和发布闸门。
- 新增 `R-045`：当前 generation adapter 固定 `stream=false`，Answer API 同步生成完成后才发布 SSE 事件；用户可能把事件 replay 误认为真实 token streaming，取消也无法保证及时终止上游生成。P=4、I=4、Score=16，RAG / Product，OPEN；实现 provider streaming/cancel，或经产品决策移出 MVP 并同步契约/UI。
- 新增 `R-046`：Git/LocalDirectory connector 只有 Worker 库实现，没有 Server source 配置、调度/手动同步、checkpoint 持久化和 Web 入口。P=4、I=4、Score=16，Ingestion / Product，OPEN；完成端到端只读 Git source 用户旅程后再宣称支持。
- `R-023` 重新确认为当前发布阻塞：production bean 仍是 `InMemoryBm25CandidateStore`，重启会丢失 lexical 候选；同时 RERANK route 未驱动真实 rerank provider。关闭条件必须包含 durable rebuild/storage 和 route-to-runtime 一致性。
- 新增 `R-047`：Web 没有 unit/component/E2E test script，契约门禁也不校验 OpenAPI operation 是否有 Controller 实现。P=4、I=4、Score=16，Quality / Web，OPEN；覆盖核心业务/权限失败路径，并增加契约-实现双向检查。
- 新增 `R-048`：空间成员后端支持 upsert，但前端只能操作已有成员，没有“选择用户并加入空间”；干净环境无法从 Web 完成多人协作。P=5、I=4、Score=20，Product / IAM / Web，OPEN；补齐 ACTIVE 用户选择、加入、角色和权限回归。
- 新增 `R-049`：来源、摄取任务和索引页面只覆盖短列表 happy path；多文件不逐项等待终态，任务/索引只显示前 5 条，缺少重试、重放、同步、归档/删除和索引回滚。P=5、I=4、Score=20，Product / Ingestion / Web，OPEN；以持久来源库和可恢复任务中心取代截断列表。
- 新增 `R-050`：Citation preview 客户端丢弃服务端 provenance 响应且不展示来源正文；Chunk Studio/Playground/Run 依赖手填 UUID/hash，普通用户不能完成核验与调试。P=5、I=4、Score=20，Product / Provenance / Web，OPEN；提供受权正文预览和业务对象间导航，内部标识仅作为高级诊断信息。
- 新增 `R-051`：Web 无 URL Router、页面状态恢复和 cursor 分页，资源超过 100 或任务/索引超过 5 时会被静默遗漏；刷新后工作上下文丢失。P=4、I=3、Score=12，Product / Web，OPEN；建立路由、可恢复筛选/选择状态和规模化分页测试。
