# Phase 6 Checklist：评估、观测、安全与恢复

- 阶段：Phase 6
- 状态：in-progress
- 冻结日期：2026-08-22
- 阶段基线：`0fe22db`
- 已接受 ADR：10 项；Phase 5 已完成闭环
- 范围：持续评估、全链路观测、提示注入/上传/越权/供应链安全收敛、隔离恢复、真实容量、保留删除和 on-call 演练。
- 非范围：release、生产迁移、启用云端出境、接受新 ADR/许可证、引入第二套业务 backend。

## 不可变退出门槛

每项证据必须同时记录：dataset/fixture version、config version、environment/machine、exact command、evidence path、owner、threshold、failure action。所有运行记录必须包含 code commit、schema/config hash、时间、seed/concurrency 和是否使用真实或合成数据。

| ID | 门槛 | 成功阈值 | Owner | 证据 | 失败处置 |
|---|---|---:|---|---|---|
| P6-EVAL-01 | 版本化评估用例 | `>=120` cases；覆盖 Markdown/table/PDF/OCR/多段多文档/同名相似/时间版本冲突/无答案/权限/注入/恶意文档/跨空间 | Quality | `tests/evaluation/phase6-*` + evaluation report | 补 fixture/人工标注，未达标不得关闭 |
| P6-EVAL-02 | Retrieval quality | Recall@10 `>=0.90`，MRR@10 `>=0.75`，按切片报告 | Retrieval | retrieval evidence | 固定配置回滚；候选 index 不得 ACTIVE |
| P6-EVAL-03 | Generation quality | citation precision、claim faithfulness、abstention accuracy 均 `>=0.90` | RAG / Quality | baseline/candidate report | 逐 case 修正或回滚 prompt/model/retrieval |
| P6-EVAL-04 | 人工/red-team 复核 | 所有安全/拒答/OCR/冲突切片有人工复核；无未解释退化 | Security / Quality | review manifest + red-team report | 生成 P1 issue，阻止阶段闭环 |
| P6-SEC-01 | 空间与证据隔离 | cross-space leakage `0`；Evidence 外引用 `0` | Security | security evaluation | 立即阻断并保留失败样本，不得降级阈值 |
| P6-SEC-02 | 出境与 Agent 安全 | unauthorized cloud call `0`；Shell/SQL/任意网络/外部写入 `0`；SSRF 绕过 `0` | Security | security evidence + threat review | fail-closed，修复后重跑全安全矩阵 |
| P6-SEC-03 | 上传/解析/提示注入 | zip bomb、路径穿越、XXE、parser/OCR timeout/resource bypass、prompt injection 关键样本全部拒绝或隔离 | Ingestion / Security | malicious corpus + test report | 未隔离不得进入 parser/index |
| P6-SEC-04 | 供应链 | Critical/High 漏洞为 `0`，或有用户明确接受的带 owner/期限/补偿控制记录；许可证可追溯 | Compliance | CI SBOM/SCA/license report | 停止合并/升级/记录例外，不自行接受 |
| P6-OBS-01 | 关键服务观测 | Server/Worker/PG/RabbitMQ/Qdrant/Valkey/Object Storage/Provider 指标、日志、Trace 可关联 | Operations | OTel/metrics/dashboard evidence | 缺信号不关闭对应服务门槛 |
| P6-OBS-02 | 在线性能 | non-AI API p95 `<300ms`；SSE first event p95 `<500ms`，不含模型排队/TTFT | Performance | load evidence | 降载/限流/修复后重测 |
| P6-OBS-03 | Retrieval capacity | 真实 embedding 维度、1M child chunks、过滤/并发混合负载 retrieval p95 `<1.5s` | Retrieval / Performance | capacity report | 不得使用 8 维合成向量结论替代 |
| P6-OBS-04 | On-call 定位 | 规定故障可仅依靠 Dashboard + Runbook 定位，P1 告警有 owner/影响/链接 | Operations | drill report + dashboard/runbook links | 增补信号或 Runbook，重复演练 |
| P6-REC-01 | 数据恢复 | RPO `<=24h`；RTO `<=4h` | Operations | isolated restore report | 记录差异/人工步骤，修复后重演 |
| P6-REC-02 | 恢复覆盖 | 完整恢复、PG 单点、Qdrant 丢失重建、对象缺失、active index 回滚、tombstone 重放均有证据 | Operations / Retrieval | recovery evidence | 不得以 backup 命令成功替代恢复证据 |
| P6-OPS-01 | Retention/deletion | retention job、audit export、cost report、SSE event cleanup 可执行且 space-scoped | Platform / Operations | migration/test/run evidence | 失败任务进入可观测 retry/DLQ |
| P6-EXIT-01 | 风险清零 | 未解决 P0/P1 安全问题 `0`；所有退出门槛有链接证据 | Orchestrator | status/risk/trace/retrospective | 保持 in-progress，列出缺口 |

## 必跑仓库门禁

- `mvn -q test`
- `python scripts/ci/contract_test.py`
- `python scripts/ci/architecture_check.py`
- `python scripts/ci/format_check.py`
- `python scripts/ci/check_markdown_links.py`
- `python scripts/ci/secret_scan.py`
- `python scripts/ci/dependency_inventory.py --require-lockfiles`
- Phase 6 evaluation/security/performance/recovery scripts and targeted Testcontainers tests
- GitHub Actions quality workflow including SBOM/Grype

## 阶段规则

1. 评估结果以 RAGForge Evaluation Run 为真相；Promptfoo 只能作为矩阵/red-team 执行器，Langfuse 只能作为可选 OTel consumer。
2. 任何 retrieval/prompt/model/tool/parser 变更必须保留 baseline/candidate、版本和退化切片。
3. 不把 8 维向量、单请求、合成延迟或 Phase 5 单 fixture 外推为容量结论。
4. 恢复演练只使用隔离基础设施和合成数据；不得接入生产数据源。
5. P0/P1、跨空间、Evidence 外引用和未授权出境任一失败时立即 fail-closed。
