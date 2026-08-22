# Phase 5 Retrospective：带引用问答与只读 Agent

- 日期：2026-08-22
- 阶段状态：blocked（授权/provider/material seam 和本地门禁完成，生产回答 graph 组装与真实 E2E 待完成）
- 当前验收 HEAD：以当前 `main` HEAD 为准；本轮 worker commit `75082bc` 已合并
- 阶段执行计划：[`PHASE_5_EXECUTION_PLAN.md`](../phase-5/PHASE_5_EXECUTION_PLAN.md)
- 阶段清单：[`PHASE_5_CHECKLIST.md`](../../03-delivery/PHASE_5_CHECKLIST.md)

## 事实与质量数据

- 合同测试检查 21 个 artifact、52 个 unittest；Phase 5 定向合同 10/10。
- 根 Maven Server + Worker 通过 28/28，Flyway V1–V12 在 Testcontainers PostgreSQL 上成功；Web `tsc --noEmit` 与 Vite build 成功。
- 合成 generation dataset v1 为 12 cases。candidate citation precision、faithfulness、abstention accuracy 均为 `1.0`；baseline 为 `0.7273/0.5833/0.0`。完整结果见 [`phase5-generation-evaluation.json`](../../../tests/evidence/phase5-generation-evaluation.json)。
- 安全定向证据为 contract 10/10、AgentToolSecurity 9/9、answer/security 19/19；未授权云调用、跨空间泄漏、Evidence 外引用、SSRF 绕过、Shell/SQL/外部写入、敏感审计字段均为 `0`。见 [`phase5-security.json`](../../../tests/evidence/phase5-security.json)。
- 合成性能证据 E2E p50/p95 为 `79.7/88.8ms`、TTFT p50 `29.4ms`、input/output `1828/419`、估算成本 `0.008`、provider calls `12`。retrieval/generation 使用代理测量，见 [`phase5-performance.json`](../../../tests/evidence/phase5-performance.json)。
- GitHub Actions quality Run [`32462939899`](https://github.com/Mirror18/RAGForge/actions/runs/32462939899) 对 `e88a654` 全绿；Maven、Phase 5 生成/性能/安全、证据上传、Phase 3/4、Web、Syft SBOM 与 Grype 均成功。
- 本轮 `mvn -f apps/server/pom.xml test`（JDK 21）通过 185/185；Testcontainers PostgreSQL/Valkey/Qdrant 与本地 Ollama acceptance 通过。新增授权上下文、provider embedding HTTP、revision/artifact material resolver 测试均通过。

## Keep

- 以 Evidence Bundle 和 evidence ID allow-list 为引用唯一真相；citation token 不接受 URL、文件名、正文或跨空间 token。
- 将取消、超时、provider 不可用、证据不足和工具失败全部映射为结构化状态；生产 graph 缺依赖时拒答而不是生成伪答案。
- 将答案 claims/citations/abstentions/events 写入空间复合外键约束的 V12，并通过 hash/opaque ref 保护审计和历史预览。
- 工具采用严格 allow-list、SSRF DNS/重定向校验、MIME/字节上限和跨空间授权；安全证据可由脚本重跑。

## Problem

- 生产 Spring graph 目前安装 fail-closed ports。仓库已有 typed session-to-space authorizer、既有 provider connection 的 embedding adapter 和 revision/artifact material resolver contract，但尚无实际 material service bean、完整 retrieval/prompt/generation graph、受控 provider route/credential 数据。因此本阶段不能声称真实生产回答已经可用。
- `RunEventStore` 的 Last-Event-ID replay 仍为进程内实现；答案历史已持久化，但进程重启恢复 SSE 事件尚无演练。
- 质量与性能均基于小型 deterministic synthetic fixture；尚未达到 Phase 6 的 120+、人工评估、真实模型延迟/成本和 red-team 口径。

## Try / Phase 6 entry

1. 先由产品/架构 owner 决定 embedding provider、material resolver 的存储边界、会话授权入口以及云出境 route 的组合；在决定前禁止弱化 fail-closed。
2. 实现并测试真实 provider-backed embedding/retrieval/material/generation graph，要求每层重复校验 `space_id`、版本/hash、route/egress 和 trace。
3. 将 durable `answer_events` 接入重启后的 SSE replay，补 cancel/reconnect/restart 演练。
4. 扩充 120+ 数据集、人工/攻击性引用审查、prompt injection 与真实容量/成本评估，再重新审查 R-025/R-026/R-012。

## Open questions

- evidence material 是否从对象存储按 opaque `content_ref` 读取，还是由 server-side revision service 提供一次性授权读取？
- embedding provider 是否复用 Ollama/OpenAI-compatible 的 provider route，还是引入独立 embedding capability/配置版本？
- ADR-0010 的方案 A 已由用户选择；仍需明确绑定接受、material service 的实际 bean/接口 owner、provider route/credential 数据和真实 E2E 环境。

上述选择已整理为未接受的 [`ADR-0010`](../../02-architecture/adr/0010-phase5-provider-material-composition.md)；在人工接受前不执行架构绑定或生产迁移。
