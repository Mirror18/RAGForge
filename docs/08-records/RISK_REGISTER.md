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
| R-008 | 半构建 Qdrant index 被发布 | 3 | 5 | 15 | candidate state、validation、atomic active pointer、rollback | Retrieval | OPEN |
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
