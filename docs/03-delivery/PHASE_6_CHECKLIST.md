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

## 当前证据索引（2026-08-22）

- P6-EVAL-01/02/03：[`phase6-evaluation-dataset.v1.json`](../../tests/evaluation/phase6-evaluation-dataset.v1.json)、[`phase6-evaluation-report.v1.json`](../../tests/evidence/phase6-evaluation-report.v1.json)；128 cases 和 deterministic candidate runner 已通过，人工/red-team review 仍 `PENDING`。
- P6-SEC-01/02/03/04：[`phase6-security.v1.json`](../../tests/evidence/phase6-security.v1.json)、[quality run 32575757466](https://github.com/Mirror18/RAGForge/actions/runs/32575757466)；23/23 安全回归通过，Syft/Grype CI 通过，阶段 SBOM artifact `9476272419`、Grype SARIF `9476280585` 可追溯。
- P6-OBS-01/04：[`phase6-observability-assets.v1.json`](../../tests/evidence/phase6-observability-assets.v1.json)、[`phase6-observability-fault-drill.v1.json`](../../tests/evidence/phase6-observability-fault-drill.v1.json)；profile、dashboard、脱敏和告警演练已通过。
- P6-REC-01/02：[`phase6-recovery.v1.json`](../../tests/evidence/phase6-recovery.v1.json)；V14 后 RPO `0s`、RTO `11.885s`，恢复场景已覆盖。
- P6-OPS-01：[`PHASE_6_RETENTION_AUDIT_COST.md`](../../docs/05-operations/PHASE_6_RETENTION_AUDIT_COST.md)、[`phase6-operations-runtime.v1.json`](../../tests/evidence/phase6-operations-runtime.v1.json)；V14 后实现、space/time scope、审计 hash-only、cost aggregation 和 `Phase6OperationsServiceTest` 5/5 通过；隔离 server 启用 scheduler 后，带 `space_id` 的过期 synthetic SSE event 4 秒内从 1 条清理至 0 条。多实例 live fan-out 仍单独待演练。
- P6-OBS-02：[`phase6-capacity-online.v1.json`](../../tests/evidence/phase6-capacity-online.v1.json)；隔离 server `ragforge-p6-online` 使用正式 register/login session 认证创建 synthetic LOCAL_ONLY Ollama run，100 次 health API 与 100 次 SSE first-event 均无错误，non-AI p95 `28.7487ms`、SSE first-event p95 `35.9285ms`，满足阈值；cookie 仅通过环境变量注入且未写入证据。
- P6-OBS-03：[`phase6-capacity-retrieval-a2.v1.json`](../../tests/evidence/phase6-capacity-retrieval-a2.v1.json)；768 维、1M points、4-space filter、20 concurrency、Recall@10 `0.995`、p95 `119.8761ms`、error rate `0`，满足检索容量阈值；向量值为 live dimension 下的公共合成值。
- 用户授权的真实 E2E：[`phase6-real-ollama-rag-e2e.v1.json`](../../tests/evidence/phase6-real-ollama-rag-e2e.v1.json)；仅证明本地 `LOCAL_ONLY` 真实 RAG 链路，不替代 Phase 6 质量和容量门槛。
- 人工/red-team 评审 manifest：[`phase6-human-redteam-review.manifest.v1.json`](../../tests/evidence/phase6-human-redteam-review.manifest.v1.json)；当前为 `PENDING_HUMAN_REVIEW`，不得用自动化结果代签。
- Agent-assisted red-team 前置报告：[`phase6-redteam-agent-pre-review.v1.json`](../../tests/evidence/phase6-redteam-agent-pre-review.v1.json)；4 组可重跑安全/合同/工具测试共 32 tests 通过，但明确不替代人工签名。
- 多实例 live fan-out：[`ADR-0011`](../../docs/02-architecture/adr/0011-multi-instance-run-event-fanout.md) 为 `Proposed`；在用户明确接受前不得实现为绑定架构决策或宣称门槛已满足。
