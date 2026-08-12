# Phase 2 复盘：Provider、Prompt 与 Run 纵向切片

- 日期：2026-08-13
- 范围：Provider Registry、Ollama/OpenAI-compatible adapter、Prompt Version、Model Route/Space Binding、no-RAG Run/Step、SSE replay/cancel、错误分类、重试和 Usage Ledger
- 基线：`268da6386420690a6f9b8f1948f1ca690b373c48`
- 阶段闭环：`P2-EXIT-01` 至 `P2-EXIT-04` 全部满足

## Keep

- 先以 OpenAPI/event schema 固定资源边界，再串行合入迁移、adapter、API、Run 执行和绑定 enforcement；契约冲突通过测试暴露并回修，例如取消 Run 的 nullable `usageLedgerId`。
- 将 `space_id`、route/profile/prompt version 和 correlation identity 同时写入 Run、Step、Invocation、Usage，避免只在 API 层做空间校验。
- 把 cloud egress 做成 Space Binding 的显式状态，并在 Run 执行前校验 route、prompt、provider 和授权，而不是依赖 provider 侧“尽量不调用”。
- 本地模型只做单链路真实应用验收，云协议使用 loopback mock 做 20 链路并发和故障分类；两类证据职责清晰、互不冒充。
- 每个 Agent 只写自己的 worktree 和文件所有权，主 Agent 逐个审查提交后以 `--no-ff` 合并，保留实现与修复轨迹。

## Problem

- 阶段初期契约与运行时 projection 对 `usageLedgerId` 的取消语义不一致，直到新增 cancelled Run contract test 才收敛为“字段存在但可为 null”。
- Testcontainers/Valkey 测试结束时会出现连接重连 warning；不影响 84/84 结果，但说明测试上下文销毁和客户端关闭仍可进一步收敛。
- retry context 目前是进程内内存状态，满足本阶段同步演示但不满足重启后恢复；已登记为 `R-021`，不能被阶段完成掩盖。
- 本机 Ollama 证据依赖固定模型 digest 和本地服务，无法直接作为 GitHub CI 的无外部依赖门禁；CI 仅接入确定性的 cloud/concurrency/egress 三组测试。

## Try

- Phase 3 先定义 ingestion job/attempt/checkpoint 的持久化 retry command，再复用 Phase 2 的 error class、correlation 和 dedupe 语义。
- 在下一轮 CI push 后记录实际 GitHub Actions Run，确认新增 Python gates 在 Linux runner 上稳定运行。
- 为 Testcontainers 测试统一客户端生命周期策略，减少 Valkey shutdown 后的 reconnect noise，并把日志质量作为测试策略的一部分。
- 在引入内容、向量、对象和缓存查询前，把跨空间 payload/URI/key 的安全测试作为前置条件，持续保持 `R-003` OPEN 直到有真实闭环证据。

## 质量与安全数据

- Java 21 全量 Maven：84 tests，0 failures，0 errors，0 skipped。
- Python contract：25/25；Phase 2 local direct Ollama：1/1；cloud protocol：4/4；20-chain concurrency：1/1；egress isolation：5/5。
- 静态质量：format 296 tracked files、architecture、secret scan、Markdown links 全部通过。
- 真实本地应用证据：Ollama `qwen3.5:9b` digest `6488c96fa5faab64bb65cbd30d4289e20e6130ef535a93ef9a49f42eda893ea7`；Run/Step/Invocation/Usage 均成功；usage source `PROVIDER_REPORTED`；token 22/128/150；无 raw prompt/output/secret。
- 未发现新的 P0/P1 代码缺陷；剩余风险集中在未来内容隔离、进程重启 retry、模型资源约束和生产运维，不属于本阶段已完成能力。

## Phase 3 入口

Phase 3 从版本化 ingestion pipeline 开始：先落实 source connector contract、revision/job/attempt/checkpoint/outbox 责任边界，再实现文件、本地目录和 Git 的增量同步。所有新数据路径必须继续继承 Phase 2 的 `space_id`、provenance、错误分类和显式出境边界。
