# ADR-0005：Provider 抽象与显式数据出境

- Status: Accepted
- Date: 2026-08-12

## Context

本机有 Ollama 与 `qwen3.5:9b`，但硬件不适合承担商业并发；系统需预留云端 OpenAI-compatible API，同时避免知识内容被无意发送到外部。

## Decision

建立 Provider Registry、Model Profile、Model Route 和 Space Binding。每个空间默认 local-only；云端出境需显式启用并限定 route。Failover 只在空间已批准、能力兼容、出境等级相同的候选内进行。

## Consequences

- 本地和云端能力可以替换、评估和审计。
- 路由逻辑比单一模型复杂，连接测试和错误分类成为必需品。
- 本地 Provider 故障时可能直接失败，这是数据控制的有意结果。
