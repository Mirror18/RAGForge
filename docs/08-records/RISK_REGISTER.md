# 风险登记表

## 1. 评分

Probability（P）与 Impact（I）各 1–5，Score = P × I。15–25 为高，8–14 为中，1–7 为低。状态：OPEN、MITIGATING、ACCEPTED、CLOSED。

| ID | 风险 | P | I | 分数 | 缓解/验证 | Owner | 状态 |
|---|---|---:|---:|---:|---|---|---|
| R-001 | 本机 8GB VRAM 无法支撑并发生成 | 5 | 3 | 15 | 本地仅功能验收；云/Mock 做 20 并发；Provider 可替换 | Architecture | MITIGATING |
| R-002 | Obsidian YAML/标题/wikilink/路径解析失真 | 4 | 4 | 16 | 自有 Connector/contract corpus；Chunk Studio；Windows/Linux 测试 | Ingestion | OPEN |
| R-003 | 跨空间数据泄漏 | 3 | 5 | 15 | space_id 不变量、RBAC、Qdrant filter、零容忍安全测试 | Security | OPEN |
| R-004 | 云端 fallback 未授权出境 | 3 | 5 | 15 | 默认 local-only；route policy；provider spy tests；审计 | Security | OPEN |
| R-005 | 低质量引用导致“看似可信”答案 | 4 | 5 | 20 | server citation validation、evidence provenance、120-case eval | RAG | OPEN |
| R-006 | Parser/OCR 恶意文件造成 RCE/DoS | 3 | 5 | 15 | quarantine、AV、sandbox、limits、malicious corpus | Security | OPEN |
| R-007 | 消息重投造成重复索引或成本 | 4 | 4 | 16 | Outbox、幂等 key、attempt/ledger dedupe、fault tests | Platform | OPEN |
| R-008 | 半构建 Qdrant index 被发布 | 3 | 5 | 15 | candidate state、validation、atomic active pointer、rollback；Phase 4 evidence 已覆盖 | Retrieval | CLOSED |
| R-009 | 过早微服务化拖慢学习和交付 | 3 | 4 | 12 | ADR-0001、架构测试、证据驱动拆分 | Architecture | MITIGATING |
| R-010 | 第三方许可证影响未来公开/商业使用 | 3 | 5 | 15 | license gate、SBOM、reuse register、禁止限制源码复制 | Compliance | MITIGATING |
| R-011 | 框架版本过新导致生态不兼容 | 3 | 3 | 9 | Boot 3.5/Spring AI 1.1 基线；编码前 compatibility spike | Platform | MITIGATING |
| R-012 | 评估集过小或偏向个人笔记 | 4 | 4 | 16 | 多格式/无答案/冲突/安全 120 cases，独立人工复核 | Quality | OPEN |
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
