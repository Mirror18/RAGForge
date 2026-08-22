# Phase 5 Retrospective：带引用问答与只读 Agent

- 日期：2026-08-22
- 阶段状态：blocked（授权/provider/material seam、版本化 material service 和 opt-in production graph 已完成，本地门禁通过；真实 provider route/credential 与 RAG E2E 待完成）
- 当前代码验收基线：`c62e42e`；material worker `624c6df` / merge `49368e4`，graph worker `c874df3` / merge `49d9160`，记录更新提交为 `0c13eb0`、`079041a`、`5cc8329`、`fbbd38a`，上下文验收提交为 `76bf953`，durable SSE 事件提交为 `c62e42e`
- 阶段执行计划：[`PHASE_5_EXECUTION_PLAN.md`](../phase-5/PHASE_5_EXECUTION_PLAN.md)
- 阶段清单：[`PHASE_5_CHECKLIST.md`](../../03-delivery/PHASE_5_CHECKLIST.md)

## 事实与质量数据

- 合同测试检查 21 个 artifact、52 个 unittest；Phase 5 定向合同 10/10。
- 根 Maven Server + Worker 通过 28/28，Flyway V1–V13 在 Testcontainers PostgreSQL 上成功；本轮 server 全量 198/198；Web `tsc --noEmit` 与 Vite build 成功。
- 合成 generation dataset v1 为 12 cases。candidate citation precision、faithfulness、abstention accuracy 均为 `1.0`；baseline 为 `0.7273/0.5833/0.0`。完整结果见 [`phase5-generation-evaluation.json`](../../../tests/evidence/phase5-generation-evaluation.json)。
- 安全定向证据为 contract 10/10、AgentToolSecurity 9/9、answer/security 19/19；未授权云调用、跨空间泄漏、Evidence 外引用、SSRF 绕过、Shell/SQL/外部写入、敏感审计字段均为 `0`。见 [`phase5-security.json`](../../../tests/evidence/phase5-security.json)。
- 合成性能证据 E2E p50/p95 为 `79.7/88.8ms`、TTFT p50 `29.4ms`、input/output `1828/419`、估算成本 `0.008`、provider calls `12`。retrieval/generation 使用代理测量，见 [`phase5-performance.json`](../../../tests/evidence/phase5-performance.json)。
- GitHub Actions quality Run [`32550604371`](https://github.com/Mirror18/RAGForge/actions/runs/32550604371) 对 `76bf953` 全绿；Maven、Phase 5 生成/性能/安全、证据上传、Phase 3/4、Web、Syft SBOM 与 Grype 均成功。
- 本轮 `mvn -f pom.xml -pl apps/server -am test`（JDK 21）通过 198/198；`Phase5ProductionGraphContextTest` 与 durable run event replay 在隔离 Testcontainers PostgreSQL/Valkey 上通过，另有 Qdrant 与本地 Ollama acceptance 证据。新增 material service、prompt space/hash resolver、retrieval identity、graph 条件接线和 durable store 相关测试均通过。

## Keep

- 以 Evidence Bundle 和 evidence ID allow-list 为引用唯一真相；citation token 不接受 URL、文件名、正文或跨空间 token。
- 将取消、超时、provider 不可用、证据不足和工具失败全部映射为结构化状态；生产 graph 缺依赖时拒答而不是生成伪答案。
- 将答案 claims/citations/abstentions/events 写入空间复合外键约束的 V12，并通过 hash/opaque ref 保护审计和历史预览。
- 工具采用严格 allow-list、SSRF DNS/重定向校验、MIME/字节上限和跨空间授权；安全证据可由脚本重跑。

## Problem

- 生产 Spring graph 已按显式 `ragforge.object-storage.enabled=true` 组装真实 retrieval/prompt/generation/material/authorization ports；默认配置和缺少对象存储凭据时仍 fail-closed。受控 provider route/credential 数据和真实 RAG E2E 尚未具备，因此本阶段不能声称真实生产回答已经可用。
- `JdbcRunEventStore` 已将 Last-Event-ID replay 的事件和序列持久化到 PostgreSQL；当前测试以新 store 实例模拟重启并通过，仍缺真实进程重启演练、过期事件物理清理和多实例 live fan-out 验证。
- 质量与性能均基于小型 deterministic synthetic fixture；尚未达到 Phase 6 的 120+、人工评估、真实模型延迟/成本和 red-team 口径。

## Try / Phase 6 entry

1. 在 ADR-0010 仍为 Proposed 的前提下，由产品/架构 owner 完成人工接受记录，并审查 provider route/credential 的注入方式；禁止提交或打印 secret，禁止默认启用云出境。
2. 在受控 fixture/凭据环境运行真实 provider-backed embedding/retrieval/material/generation graph，要求每层重复校验 `space_id`、版本/hash、route/egress 和 trace。
3. 补真实进程重启后的 SSE reconnect/replay、过期事件清理和多实例 live fan-out 演练；V13 durable run events 已接入 replay source。
4. 扩充 120+ 数据集、人工/攻击性引用审查、prompt injection 与真实容量/成本评估，再重新审查 R-025/R-026/R-012。

## Open questions

- 当前实现复用既有对象存储与 server-side revision/artifact service；仍需真实环境验证其对象存储配置、权限与数据生命周期。
- 当前 embedding provider 复用既有 provider connection，并通过独立 embedding capability 接口接入；仍需受控 route/profile/credential fixture 验证。
- ADR-0010 的方案 A 已由用户选择；仍需明确绑定接受、provider route/credential 注入和真实 E2E 环境。

上述选择已整理为未接受的 [`ADR-0010`](../../02-architecture/adr/0010-phase5-provider-material-composition.md)；在人工接受前不执行架构绑定或生产迁移。
