# ADR-0008：评估与 LLM 可观测性

- Status: Accepted
- Date: 2026-08-12

## Context

RAG 质量不能只依赖人工感觉，也不应被某个观测产品锁定。项目需要 CI 评估、运行 Trace 和可选的专业 LLMOps 界面。

## Decision

核心评估数据、运行和指标由 RAGForge 自有；[Promptfoo](https://github.com/promptfoo/promptfoo) 作为 MIT 许可的 CI prompt/model matrix 和 red-team 工具；[Langfuse](https://github.com/langfuse/langfuse) 仅通过 OpenTelemetry/API 可选集成，放入独立 `llmops` profile，不复制 EE 代码。

## Consequences

- 移除第三方工具不会丢失质量历史。
- 需要维护自有 evaluation schema 和导入/导出 adapter。
- 完整 LLMOps profile 资源较重，不作为本机每次开发的默认依赖。

## References

- [Langfuse Spring AI via OpenTelemetry](https://langfuse.com/integrations/frameworks/spring-ai)

