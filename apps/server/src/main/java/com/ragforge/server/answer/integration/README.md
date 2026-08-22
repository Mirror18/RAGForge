# Phase 5 answer integration

本目录只提供显式生产接线，不改变 `answer` core、OpenAPI、Web 或数据库迁移。

- `ProviderBackedGenerationPort` 只解析一个已授权的 route/profile candidate，并复用现有
  `ProviderAdapterRegistry`、`EgressPolicy` 和空间 binding。它不遍历候选做 fallback；模型输出必须是
  结构化 `answer_text`/`claims`/`citation_tokens`，否则拒绝。
- `ActiveRetrievalExecutionResolver` 读取空间 active index/profile；`RetrievalServicePortAdapter`
  复用 Phase 4 `RetrievalService`，并强制要求版本化证据正文由调用方提供 `EvidenceMaterialResolver`。
  只有 metadata/contentRef 时不能生成回答。
- `ProviderBackedQueryEmbeddingProvider` 复用同一个 `ProviderAdapterRegistry` 和已批准的
  `ProviderConnection`，但只读取空间绑定的 `EMBEDDING` route；`LOCAL_ONLY` 与云出境授权分别强制校验，
  不会把 CHAT route 或其他候选当作 embedding fallback。未组装真实 embedding、检索、prompt、generation
  或 material service 时，`FailClosed*` 实现记录脱敏原因并拒绝。
- `AnswerAuthorizationContext` 由 HTTP adapter 在 `SpaceAuthorization.requireMember` 和 run owner
  校验后创建；`SessionSpaceAnswerAuthorizer` 在 answer core 再次校验 space、membership、run、correlation
  和 session expiry。context 是服务端对象，不接受 JSON 或客户端自带的 user/space 权限字段。
- `RevisionArtifactMaterialResolver` 不读取客户端正文，只把 Evidence Bundle 的 revision/contentRef/textHash
  交给 `RevisionArtifactMaterialService`；服务返回的 space、revision、ref、hash 必须逐项相等，否则拒答。
- `Phase5IntegrationConfiguration` 是工厂而非 Spring `@Configuration`。应用必须显式组装完整 port graph；
  component scanning 不会把不完整环境伪装成可用回答服务。

请求中的 `space_id`、`run_id`、`correlation_id`、route/profile 版本和 `EgressDecision` 在每个桥接层
重复校验。日志观察器只接收身份、组件、结果和原因，不接收 query、prompt、evidence material、凭据或
provider response body。
