# Phase 5 Checklist：带引用问答与只读 Agent

- 阶段：Phase 5
- 状态：blocked（Phase 5 代码与本地验收门禁完成；生产回答接线缺少必须的 embedding/material/auth provider 选择）
- 冻结日期：2026-08-21
- 统一基线：`43aa7c16668f4728e20a76863e82ddbf65cd1444`（阶段执行记录基线）；当前验收 HEAD 以证据文件 `code_commit` 为准
- 范围：在 Phase 4 检索证据之上完成可追溯的 RAG 回答、流式引用交互和三个受控只读工具。
- 非范围：可写 Agent、任意 Shell/SQL/网络、跨空间回答、生产云端出境、release、接受新 ADR/许可证。

## 不可变验收口径

| 门禁 | 阈值/不变量 | 必须证据 |
|---|---|---|
| P5-CONTRACT-01 | Answer/Claim/Citation/Abstention/ToolCall/ToolResult 与 SSE v1 schema 可解析，版本、space、correlation、幂等字段齐全；敏感正文不进入审计投影 | contracts、contract test |
| P5-EXIT-01 | citation precision >= 0.90；claim faithfulness >= 0.90；abstention accuracy >= 0.90；每个结果记录 dataset/config/model/judge 版本 | `tests/evidence/phase5-generation-evaluation.json` |
| P5-EXIT-02 | 回答只能引用本次 Evidence Bundle 的 evidence ID；Evidence 外、畸形、重复、跨空间 token 均拒绝或结构化拒答；外引用计数为 0 | citation/security tests + evidence JSON |
| P5-EXIT-03 | Agent 只能调用 `knowledge.search`、`document.read`、白名单 `web.fetch`；无 Shell、SQL、任意网络、其他空间或外部写入 | tool policy/SSRF/cross-space tests + security evidence |
| P5-EXIT-04 | cancel 幂等；CANCELLED 后无 answer delta；sequence 单调、event_id 稳定；Last-Event-ID 可重放；超时、工具失败、证据不足和 provider 降级有可理解状态 | SSE/integration tests + trace evidence |
| P5-EXIT-05 | 每次回答可追溯 `space_id/index/profile/prompt/model/run/tool` 版本；引用保留 revision、parent/child 与位置锚点，点击重新鉴权读取历史版本 | persistence/replay/audit tests |
| P5-PERF-01 | 记录 retrieval/generation/TTFT/E2E latency、input/output tokens、调用数、估算成本及 timeout/retry/degraded/cancel；不得用均值掩盖安全/拒答切片退化 | `tests/evidence/phase5-performance.json` |
| P5-SEC-01 | 未授权云调用 0；跨空间泄漏 0；Evidence 外引用 0；SSRF 私网/环回/link-local/metadata/重定向绕过 0 | `tests/evidence/phase5-security.json` |

## 任务退出条件

- [x] P5-A：Checklist、执行计划、契约和评估数据口径提交。
- [x] P5-B：RAG prompt、run/step 版本投影和兼容/回滚说明提交。
- [x] P5-C：授权检索、context budget、版本化 prompt、route/egress 检查、结构化拒答提交；生产缺少真实 embedding/material/session adapter，当前按 fail-closed 接线。
- [x] P5-D：结构化 citation token 解析、bundle allow-list、持久化 provenance 和安全拒答提交。
- [x] P5-E：SSE answer/citation/abstention/tool/usage/error/done、重连、取消和前端引用交互提交。
- [x] P5-F：三种只读工具、SSRF/白名单/输出限制、schema 校验和审计提交。
- [x] P5-G：版本化 generation/citation/abstention 数据集、baseline/candidate、质量/性能/安全证据提交。
- [x] P5-H：根 Maven、worker、web、contract、architecture、format、secret、dependency、security、evaluation、Markdown link 和 CI 本地门禁全部通过；远端 CI 需以本次 push 后 Run 为最终证据。

## 合并前强制检查

1. 所有内容查询/变更显式强制 `space_id`，服务端不信任客户端过滤。
2. 所有 prompt、profile、index、revision、artifact、model route、tool schema 和 evaluation dataset 使用不可变版本或 hash。
3. 云端出境只由空间 policy + approved route 允许；DENY 不得由 retry/fallback 绕过。
4. 模型输出的文件名、URL、正文引用和工具指令均是不可信数据。
5. 不提交真实文档、客户 prompt、凭据、个人 Obsidian 内容或未经批准的第三方源码。
6. 任意高风险 ADR、许可证接受、生产迁移、云出境启用和 release 必须停下等待用户明确批准。

## 阶段闭环记录

- 质量证据：[`phase5-generation-evaluation.json`](../../tests/evidence/phase5-generation-evaluation.json)，合成 12 cases，candidate precision/faithfulness/abstention 均为 `1.0`；不替代 Phase 6 的 120+ 与人工评估。
- 安全证据：[`phase5-security.json`](../../tests/evidence/phase5-security.json)，契约 10/10、AgentToolSecurity 9/9、回答/出境 19/19；六项安全不变量均为 `0`。
- 性能/成本证据：[`phase5-performance.json`](../../tests/evidence/phase5-performance.json)，合成 fixture：E2E p50/p95 `79.7/88.8ms`，TTFT p50 `29.4ms`，并明确标记 retrieval/generation 为代理测量。
- Retrospective：[`PHASE_5_RETROSPECTIVE.md`](../08-records/retrospectives/PHASE_5_RETROSPECTIVE.md)。

## 阻塞与阶段结论

- 已满足：本清单的合同、引用 allow-list、拒答、只读工具安全、SSE 取消/重放、答案历史与本地质量/性能/安全门禁均有可重跑证据。
- 未闭环：生产 Spring graph 当前显式使用 fail-closed ports；仓库没有真实 embedding provider、会话到 `space_id` 的 authorizer、或从版本化 artifact/content store 读取 evidence material 的 resolver。没有这些依赖，不能声称生产成功回答或关闭 Phase 5。
- 下一入口：Phase 6 先完成 provider/material/auth seam 的架构决定与真实受控端到端验收，再补 120+ generation/evaluation、SSE 重启恢复和 step/model provenance 演练。
