# Phase 6 Retrospective：评估、观测、安全与恢复

- 日期：2026-08-23
- 状态：`completed-with-explicit-waiver`
- 阶段基线：`0fe22db5979aa5ae7892165c227a5c8a484bdfb9`；当前主线实现验证 SHA：`bde93ebe9be2b0b9e2614a0cc43baf216285c1b6`

## Done

- 冻结了 Phase 6 checklist、执行计划、证据字段和所有权边界。
- 建立 128 个版本化公共合成评估用例和可复现 runner；安全、权限、冲突、无答案和注入切片已进入数据集。
- 完成 OTel/Prometheus/Grafana/Loki/Tempo profile、告警、dashboard、脱敏验证和未授权出境 fault drill。
- 完成 Phase 6 安全 corpus、出境/合同/AgentToolSecurity 回归；未发现跨空间、Evidence 外引用、未授权出境、SSRF 或工具越权。
- 完成隔离恢复演练：V14 后 RPO `0s`、RTO `11.885s`，覆盖 PostgreSQL、Qdrant、对象、active index、tombstone/delete ledger 和 outbox/job 幂等。
- 实现 retention、space-scoped audit export、cost aggregation 和 SSE event cleanup。
- 修复 Phase6OperationsService 的构造注入、scheduling 启用和空间限定问题，并完成 V14 隔离 scheduler 演练：带 `space_id` 的过期 synthetic event 1 → 0，目标 event 4 秒后不存在。
- 在用户明确授权下完成真实本地 Ollama `LOCAL_ONLY` RAG E2E；复用 provider connection，由 revision/artifact service 提供 material，并保留 citation/provenance。
- 在隔离 Compose server 上通过正式 register/login session 认证创建 synthetic LOCAL_ONLY run，完成 100 次 non-AI API 与 100 次 SSE first-event 测量；p95 分别为 `28.7487ms` 和 `35.9285ms`，cookie 未进入证据。
- 增加 loopback `LOCAL_ONLY` Ollama streaming probe；真实 `qwen3.5:9b` standalone TTFT `9130.6742ms`、provider total `11456.3744ms`、wall `11475.2584ms`、`19.6176 tokens/s`、usage `35/46/81`，输出仅保留 hash/长度。
- 增加真实 revision/artifact-backed RAG graph stream boundary evidence；graph-to-first-token `1675.9884ms`、provider TTFT `1560.7450ms`、provider total `4847.3558ms`、wall `4854.6037ms`、usage `193/98/291`，并显式标注生产同步 `GenerationPort` 尚未暴露 streaming。
- 增加本地 Ollama 2 并发成本证据；4 个 measured requests 全部成功，TTFT p50/p95 `1482.8559/2688.2120ms`、wall p50/p95 `2762.1378/4013.6133ms`、usage `144/108/252`、retry/cancel/timeout `0`、估算成本 `0 USD`。
- 接受 ADR-0011 并实现多实例 run-event live fan-out；两个独立 Spring server context + 共享隔离 PostgreSQL/Valkey 的跨实例投递、空间隔离、提交后发布、回滚、乱序补洞、durable replay、最小 envelope 和 listener shutdown 均有专项测试与证据。
- 本轮主线提交 `4481bef` 的 GitHub Actions quality Run `32579989036` 全绿，并生成 SBOM `9477533715`、Grype `9477541287`、Phase 3/4/5 evidence artifacts；本轮证据可追溯到 CI。
- 处理了 CI 暴露的取消执行竞态：先提交 `CANCELLED` 再 signal provider，并以 `REQUIRES_NEW` 隔离乐观锁失败，避免外层事务被标记 rollback-only；worker commits `f02d14d`/`f086169`，main merges `08b3bfa`/`bde93eb`，本地 server/root 全量测试与 quality Run `32586867110` 全部通过。
- 功能验证基线 `bde93eb` 及其记录提交 `6ec5d9d` 均已推送且工作区干净；quality Run `32586867110` 与记录提交后的 `32587259456` 均全绿，Phase 6 自动化/安全/供应链/评估门禁证据已更新。
- 2026-08-23，项目用户明确批准豁免 P6-EVAL-04 的至少 2 名人审 + 1 名红队评审签名门槛。manifest 记录为 `PASS_WITH_EXPLICIT_WAIVER`，签名保持为空；该决定不伪造人工复核，不把 Agent-assisted pre-review 当作人工签名，并接受 R-005/R-012 的残余风险。

## 豁免后的残余证据

- 评估 candidate 的确定性指标全部为 1.0；人工/red-team review 未执行，已由项目用户明确批准豁免。不得把 runner 结果写成真实模型质量结论；R-005/R-012 保持为可重新开启的残余风险。
- 已执行 Agent-assisted adversarial pre-review：Python 安全/合同 23 tests + AgentToolSecurity 9 tests 均通过；报告明确保留人工 review 状态，不能作为人工签名。
- 真实 Ollama embedding 维度 768 和 1,000,000 点 Qdrant 混合检索已取得有效证据：Recall@10 `0.995`、p95 `119.8761ms`、20 并发错误率 `0`；向量值仍是 live dimension 下的公共合成值。
- 在线 API/SSE 性能门槛已取得认证隔离运行证据；真实 RAG graph stream boundary 已测量，但生产同步 `GenerationPort` streaming 仍未实现，不将边界探针冒充为生产 API 能力。
- 本地 2 并发成本已观测且估算为 0 USD；云端价格、云 route 和生产级并发模型仍未授权/未测量。
- retention/cleanup 的空间限定单实例受控定时运行和多实例 live fan-out 已通过隔离演练；证据不外推生产容量、云端部署或跨区域语义。

## Learnings

- “有 runner”不等于“有人工质量结论”；证据状态必须把 deterministic、synthetic、real、manual 分开记录。
- 容量演练的真实维度探针、批量写入、混合查询和在线 API/SSE 探针应拆成可独立重试的阶段，避免一个 Qdrant 超时阻塞所有指标。
- Windows 子进程的 PATH 大小写和本地工具安装位置会影响供应链门禁；runner 应把工具可见性作为可验证输入，而不是只依赖 shell 中的命令解析。
- 观测 profile 的运行时查询和告警 fault drill 比静态配置更接近可运营证据，但 dashboard 视觉验收仍需独立记录。

## Next actions

1. 后续模型、prompt、retrieval、provider、parser 或安全策略变更时，重新开启独立人工/red-team review。
2. 若重新开启复核，补齐四个切片、六类 red-team 场景、至少 2 名人审和 1 名红队签名，并重跑质量门禁。
3. 云端与生产质量/容量仍保持明确边界，不得由本次豁免或本地证据外推。

## Closure rule

本次以用户明确批准的治理例外关闭阶段：自动化、质量、安全、供应链和恢复证据已固化；人工/red-team 门槛以 `PASS_WITH_EXPLICIT_WAIVER` 记录，R-005/R-012 作为已接受残余风险保留。阶段闭环不代表人工复核已执行；后续高风险 RAG 或安全变更必须重新开启复核。
