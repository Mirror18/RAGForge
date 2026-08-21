# Phase 5 answer integration

本目录只提供显式生产接线，不改变 `answer` core、OpenAPI、Web 或数据库迁移。

- `ProviderBackedGenerationPort` 只解析一个已授权的 route/profile candidate，并复用现有
  `ProviderAdapterRegistry`、`EgressPolicy` 和空间 binding。它不遍历候选做 fallback；模型输出必须是
  结构化 `answer_text`/`claims`/`citation_tokens`，否则拒绝。
- `ActiveRetrievalExecutionResolver` 读取空间 active index/profile；`RetrievalServicePortAdapter`
  复用 Phase 4 `RetrievalService`，并强制要求版本化证据正文由调用方提供 `EvidenceMaterialResolver`。
  只有 metadata/contentRef 时不能生成回答。
- 当前没有真实 embedding ProviderAdapter。`FailClosedQueryEmbeddingProvider` 永不返回伪向量；
  未配置 embedding、检索、prompt、generation 或会话空间授权时，`FailClosed*` 实现记录脱敏原因并拒绝。
- `Phase5IntegrationConfiguration` 是工厂而非 Spring `@Configuration`。应用必须显式组装完整 port graph；
  component scanning 不会把不完整环境伪装成可用回答服务。

请求中的 `space_id`、`run_id`、`correlation_id`、route/profile 版本和 `EgressDecision` 在每个桥接层
重复校验。日志观察器只接收身份、组件、结果和原因，不接收 query、prompt、evidence material、凭据或
provider response body。
