# Phase 5 Retrospective：带引用问答与只读 Agent

- 日期：2026-08-22
- 阶段状态：completed（ADR-0010 Accepted；方案 A、既有 provider connection、revision/artifact material service 与本地 LOCAL_ONLY Ollama RAG E2E 已完成）
- 当前代码验收基线：`600960f`；ADR 接受提交 `246f993`，真实 Ollama RAG 生成审计提交 `600960f`
- 阶段执行计划：[`PHASE_5_EXECUTION_PLAN.md`](../phase-5/PHASE_5_EXECUTION_PLAN.md)
- 阶段清单：[`PHASE_5_CHECKLIST.md`](../../03-delivery/PHASE_5_CHECKLIST.md)

## 事实与质量数据

- 合同测试检查 21 个 artifact、52 个 unittest；Phase 5 定向合同 10/10。
- 根 Maven Server + Worker 通过 28/28，Flyway V1–V13 在 Testcontainers PostgreSQL 上成功；本轮 server 全量 198/198；Web `tsc --noEmit` 与 Vite build 成功。
- 合成 generation dataset v1 为 12 cases。candidate citation precision、faithfulness、abstention accuracy 均为 `1.0`；baseline 为 `0.7273/0.5833/0.0`。完整结果见 [`phase5-generation-evaluation.json`](../../../tests/evidence/phase5-generation-evaluation.json)。
- 安全定向证据为 contract 10/10、AgentToolSecurity 9/9、answer/security 19/19；未授权云调用、跨空间泄漏、Evidence 外引用、SSRF 绕过、Shell/SQL/外部写入、敏感审计字段均为 `0`。见 [`phase5-security.json`](../../../tests/evidence/phase5-security.json)。
- 合成性能证据 E2E p50/p95 为 `79.7/88.8ms`、TTFT p50 `29.4ms`、input/output `1828/419`、估算成本 `0.008`、provider calls `12`。retrieval/generation 使用代理测量，见 [`phase5-performance.json`](../../../tests/evidence/phase5-performance.json)。
- 真实 E2E [`phase5-real-ollama-rag-e2e.v1.json`](../../../tests/evidence/phase5-real-ollama-rag-e2e.v1.json) 通过：Ollama chat/embedding digest、LOCAL_ONLY、revision/artifact material、citation/provenance、usage 和空间隔离均验证；retrieval/generation/E2E `129.0/3986.7/6423.9ms`，token `196/101/297`，provider usage persisted，fault counters 全为 `0`；同步非流式 TTFT 为 `NOT_MEASURED`。
- 事件演练 [`phase5-run-events-restart-cancel.v1.json`](../../../tests/evidence/phase5-run-events-restart-cancel.v1.json) 通过：server 真实进程重启后 health `200`，durable replay、cursor、序列、身份、取消幂等和 late delta 拒绝均通过。
- 当前提交尚无新的 GitHub Actions 结果；旧 Run [`32550604371`](https://github.com/Mirror18/RAGForge/actions/runs/32550604371) 仅作为历史基线，不替代 `600960f` 的远程验证。旧 CI 的 Syft/Grype 成功，当前本地 SBOM 因缺少 `syft/trivy` 未执行。
- 根 Maven 全量 reports 汇总 `227` tests、`0` failures、`0` errors、`1` skipped；`Phase5ProductionGraphContextTest`、durable replay、真实 Ollama E2E 与新增 generation audit 均通过。

## Keep

- 以 Evidence Bundle 和 evidence ID allow-list 为引用唯一真相；citation token 不接受 URL、文件名、正文或跨空间 token。
- 将取消、超时、provider 不可用、证据不足和工具失败全部映射为结构化状态；生产 graph 缺依赖时拒答而不是生成伪答案。
- 将答案 claims/citations/abstentions/events 写入空间复合外键约束的 V12，并通过 hash/opaque ref 保护审计和历史预览。
- 工具采用严格 allow-list、SSRF DNS/重定向校验、MIME/字节上限和跨空间授权；安全证据可由脚本重跑。

## Problem

- 生产 Spring graph 已按显式 `ragforge.object-storage.enabled=true` 组装真实 retrieval/prompt/generation/material/authorization ports；用户授权本地 Ollama route 后，真实单空间 RAG 生成和 generation audit 已通过，默认配置及缺少对象存储时仍 fail-closed。
- `JdbcRunEventStore` 已将 Last-Event-ID replay 的事件和序列持久化到 PostgreSQL；真实进程重启演练已通过，过期事件物理清理和多实例 live fan-out 仍转入 Phase 6。
- 质量与性能均基于小型 deterministic synthetic fixture；尚未达到 Phase 6 的 120+、人工评估、真实模型延迟/成本和 red-team 口径。

## Try / Phase 6 entry

1. 以 Phase 6 计划扩充 120+ 数据集、人工/攻击性引用审查和 prompt injection/red-team 评估。
2. 补真实并发容量、成本、流式 TTFT、过期事件清理和多实例 live fan-out 演练。
3. 推送后复核当前提交的 GitHub Actions Maven/Web/contract/security/evaluation/SBOM/Grype 结果，再更新发布级证据。

## Open questions

- 当前实现复用既有对象存储与 server-side revision/artifact service；仍需真实环境验证其对象存储配置、权限与数据生命周期。
- 当前 embedding provider 复用既有 provider connection，并通过独立 embedding capability 接口接入；本阶段已用受控本地 route/profile/credential fixture 完成验证，云 route 仍未启用。
- ADR-0010 的方案 A 已由用户明确接受；本阶段仅授权本地 `LOCAL_ONLY` Ollama，云 route/生产凭据仍需未来显式决策。

上述选择记录于已接受的 [`ADR-0010`](../../02-architecture/adr/0010-phase5-provider-material-composition.md)；本阶段未启用云出境、生产迁移或 release。
