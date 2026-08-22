# ADR-0010：Phase 5 Provider、Evidence Material 与会话授权组合边界

- Status: Proposed（未接受，不得作为绑定架构执行）
- Date: 2026-08-22

## Context

Phase 5 的 `RAGAnswerService` 已有明确的 `QueryEmbeddingProvider`、`RetrievalPort`、`RagPromptPort`、`GenerationPort`、`SpaceAuthorizer` 和 evidence material resolver 接口。当前仓库已有聊天 provider registry 和 Phase 4 检索服务，但尚无真实 embedding capability；V9 的 `content_ref` 是不可直接读取的 opaque reference；现有 HTTP answer controller 的会话授权尚未组合进 answer core。生产 Spring graph 因此必须 fail-closed。

如果直接在 answer service 内部读取对象、接受客户端 material 或把聊天 route 当作 embedding route，会破坏空间隔离、版本追溯或显式出境边界。

## Decision

本 ADR 仅提出待接受的组合方案，不改变当前实现：

1. 在 provider capability 层增加独立的 embedding contract 和 `EMBEDDING` route/profile purpose；禁止把 chat adapter 的请求格式或模型能力隐式当作 embedding 能力。
2. 在模块化单体内提供 server-owned、space-scoped、revision-immutable 的 `EvidenceMaterialResolver`。它只接受服务端生成的 `content_ref`，重新校验 `space_id`、revision/artifact 状态、text hash 和 trace；不得接受 URL、文件路径、原始正文或客户端凭据。
3. answer API 复用现有 Session/RBAC 的服务端授权上下文，生成不可伪造的 `SpaceAuthorizer` 输入；controller 的 path `space_id`、session membership、run owner 和 answer request 的 `space_id` 必须全部一致。
4. embedding、material、generation 三个 capability 都通过显式端口组装；任一 capability 缺失时保持结构化拒答。云端 route 仍受 ADR-0005 的空间出境开关和 approved route 约束，禁止静默 fallback。
5. 本 ADR 不决定对象存储读取协议、具体 embedding model 或云 provider；这些需要 capability contract、license/credential review 和真实端到端验收后另行记录。

## Consequences

- 可以让生产回答从 fail-closed 安全地过渡到 provider-backed，而不把业务 backend 拆成第二套。
- 需要新增 embedding provider contract、material 读取授权测试、真实空间 session E2E 和成本/容量证据。
- `content_ref` 的实际存储/读取 owner、embedding route 的 capability schema 和 session-to-space adapter 仍需要人工选择；在 ADR 被接受前不得关闭 Phase 5。

## Alternatives

- 让 `RAGAnswerService` 直接调用 Ollama/OpenAI HTTP：拒绝，绕过 provider registry、route/egress 和 capability 版本。
- 让前端或模型直接提交 evidence 正文/URL：拒绝，无法证明历史 revision、空间和 hash 一致性。
- 将 retrieval/material/answer 拆成新的业务 backend：拒绝，违反当前模块化单体边界，除非另有 ADR 证明拆分必要。

## Required human decision

接受本 ADR 前，必须明确：

- embedding capability 是否复用现有 provider connection 但新增 purpose/capability，还是采用独立 provider connection；
- evidence material 是由 server-side revision/artifact service 读取，还是由对象存储 adapter 读取；
- answer API 如何把现有 Session/RBAC 授权上下文传递到 core `SpaceAuthorizer`。

## References

- [ADR-0004：空间级 RBAC](0004-space-level-rbac.md)
- [ADR-0005：Provider 抽象与显式数据出境](0005-provider-abstraction-and-egress.md)
- [Phase 5 Checklist](../../03-delivery/PHASE_5_CHECKLIST.md)
- [Phase 5 Retrospective](../../08-records/retrospectives/PHASE_5_RETROSPECTIVE.md)
