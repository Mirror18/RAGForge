# ADR-0010：Phase 5 Provider、Evidence Material 与会话授权组合边界

- Status: Accepted（2026-08-22，经用户明确接受）
- Date: 2026-08-22

## Context

Phase 5 的 `RAGAnswerService` 已有明确的 `QueryEmbeddingProvider`、`RetrievalPort`、`RagPromptPort`、`GenerationPort`、`SpaceAuthorizer` 和 evidence material resolver 接口。当前仓库已有聊天 provider registry 和 Phase 4 检索服务，但尚无真实 embedding capability；V9 的 `content_ref` 是不可直接读取的 opaque reference；现有 HTTP answer controller 的会话授权尚未组合进 answer core。生产 Spring graph 因此必须 fail-closed。

如果直接在 answer service 内部读取对象、接受客户端 material 或把聊天 route 当作 embedding route，会破坏空间隔离、版本追溯或显式出境边界。

## Decision

用户已明确接受复用现有 provider connection、由 revision/artifact service 提供 evidence material，并采用授权方案 A（typed context）。本 ADR 现作为 Phase 5 的绑定架构决策；生产回答仍仅在显式 object-storage opt-in、空间级 route binding 和 capability 校验全部满足时组装，默认图继续 fail-closed。

本 ADR 提出以下组合方案：

1. 在 provider capability 层增加独立的 embedding contract 和 `EMBEDDING` route/profile purpose；禁止把 chat adapter 的请求格式或模型能力隐式当作 embedding 能力。
2. 在模块化单体内提供 server-owned、space-scoped、revision-immutable 的 `EvidenceMaterialResolver`。它只接受服务端生成的 `content_ref`，重新校验 `space_id`、revision/artifact 状态、text hash 和 trace；不得接受 URL、文件路径、原始正文或客户端凭据。
3. answer API 复用现有 Session/RBAC 的服务端授权上下文，生成不可伪造的 `SpaceAuthorizer` 输入；controller 的 path `space_id`、session membership、run owner 和 answer request 的 `space_id` 必须全部一致。具体传入方式见下文，推荐方案 A。
4. embedding、material、generation 三个 capability 都通过显式端口组装；任一 capability 缺失时保持结构化拒答。云端 route 仍受 ADR-0005 的空间出境开关和 approved route 约束，禁止静默 fallback。
5. 本 ADR 不决定对象存储读取协议、具体 embedding model 或云 provider；这些需要 capability contract、license/credential review 和真实端到端验收后另行记录。

## Consequences

- 可以让生产回答从 fail-closed 安全地过渡到 provider-backed，而不把业务 backend 拆成第二套。
- 需要新增 embedding provider contract、material 读取授权测试、真实空间 session E2E 和成本/容量证据。
- `content_ref` 的实际存储/读取 owner、embedding route 的 capability schema 和 session-to-space adapter 仍需要人工选择；在 ADR 被接受前不得关闭 Phase 5。

## Authorization context options

### 方案 A：显式 typed context（推荐）

HTTP controller 使用现有 `@AuthenticationPrincipal SessionPrincipal`，先调用 `SpaceAuthorization.requireMember(pathSpaceId, principal)`，再创建只允许服务端构造的 `AnswerAuthorizationContext`，至少包含：`userId`、`sessionId`、`authorizedSpaceId`、`spaceRole`、`runId`、`correlationId`、`traceId` 和短时 `expiresAt`。调用形式为：

```text
AnswerAuthorizationContext context = answerAuthorizationContextFactory.issue(
    principal, pathSpaceId, runId, correlationId, traceId);
answers.answer(answerRequest, context);
```

`SpaceAuthorizer` 必须同时校验 context 的空间、run、用户/session、过期时间，并再次读取服务端 membership/run ownership；模型、工具或客户端 body 不能构造或替换 context。该方案与现有 [`ToolExecutionContext`](../../../apps/server/src/main/java/com/ragforge/server/agent/ToolExecutionContext.java) 一致，显式、可测试，且不会依赖异步线程的 ambient security state。

### 方案 B：短时、不可转移的授权 grant（适合异步）

认证边界在同步请求中完成 membership/run 检查后，签发 `SpaceAccessGrant`：`grantId`、`userId`、`sessionId`、`spaceId`、`runId`、允许动作、`issuedAt`、`expiresAt`、nonce 和签名。任务/worker 只接收 grant，不接收原始 session cookie；core 校验签名、TTL、nonce 一次性使用、动作和空间，再调用 revision/artifact service。适合生成任务跨线程或跨进程，但引入签名密钥轮换、grant 撤销和 replay 防护成本。

### 方案 C：Spring Security ambient context（不推荐作为 core 入口）

controller 只把 `AnswerRequest` 传入 service，由 adapter 从 `SecurityContextHolder` 取得 `SessionPrincipal` 并调用 `SpaceAuthorization`。实现改动小，但异步 generation、SSE 回调和 worker 线程可能丢失 context；测试和审计也更依赖隐式线程状态。若保留，只能作为 HTTP adapter，不能让 `RAGAnswerService` 直接读取 thread-local。

### 比较与建议

| 方案 | 同步 HTTP | 异步/跨进程 | 显式可测试 | 主要风险 |
|---|---:|---:|---:|---|
| A typed context | 强 | 中（可序列化后重新签发） | 强 | 需要扩展 answer 调用签名 |
| B signed grant | 强 | 强 | 强 | 密钥、TTL、撤销和 replay 管理 |
| C ambient context | 强 | 弱 | 弱 | 异步丢失授权、隐式状态 |

建议先采用 A；若 Phase 6 将 generation 迁移到独立 worker，再由新增 ADR 将 A 的边界转换为 B。无论选择哪种方案，path/body 的 `space_id` 都不能成为授权来源，必须由服务端 session/grant 和数据库 membership/run ownership 共同决定。

## Alternatives

- 让 `RAGAnswerService` 直接调用 Ollama/OpenAI HTTP：拒绝，绕过 provider registry、route/egress 和 capability 版本。
- 让前端或模型直接提交 evidence 正文/URL：拒绝，无法证明历史 revision、空间和 hash 一致性。
- 将 retrieval/material/answer 拆成新的业务 backend：拒绝，违反当前模块化单体边界，除非另有 ADR 证明拆分必要。

## Required human decision

本 ADR 的绑定范围已由用户明确接受：

- embedding capability 复用现有 provider connection，并新增 purpose/capability 约束；
- evidence material 由 server-side revision/artifact service 提供；
- 授权传入方式采用方案 A；生产启用条件仍需逐项通过真实 provider-backed RAG E2E、空间隔离、出境和质量门禁。本 ADR 接受不等于阶段验收完成。

## References

- [ADR-0004：空间级 RBAC](0004-space-level-rbac.md)
- [ADR-0005：Provider 抽象与显式数据出境](0005-provider-abstraction-and-egress.md)
- [Phase 5 Checklist](../../03-delivery/PHASE_5_CHECKLIST.md)
- [Phase 5 Retrospective](../../08-records/retrospectives/PHASE_5_RETROSPECTIVE.md)
