# Implementation Plan: 仅云端模型

**Branch**: `002-cloud-only-models` | **Date**: 2026-10-12 | **Spec**: [spec.md](./spec.md)

## Summary

删除本地 Ollama 产品路径。Chat、Embedding 均使用空间已授权的 OpenAI 兼容云端 API。安全不变量从“默认 local-only”改为“默认不得出境，直到空间授权云端连接”。

## Technical Context

**Language/Version**: Java 21、Vue 3

**Primary Dependencies**: 现有 Provider Registry、Space Binding、EgressPolicy

**Testing**: `ProviderAdapterHttpTest`、出境隔离测试、Web 合成 fixture

**Constraints**: 不得静默 fallback；不得在未授权时发送 chunk 正文

## Constitution Check

- 云端出境显式：通过（本功能把它变成唯一模型路径）
- 结构化引用不变：通过

## Implementation Notes

1. 前端去掉 LOCAL/Ollama 切换和表单选项。
2. 文档与风险表改写 RF-PRV-001、R-001、R-004。
3. Java `OllamaProviderAdapter` 及 Compose ollama profile 在 ADR-0014 Accepted 后的实现卡删除；本轮文档与 UI 先切断入口。
4. 保留 adapter SPI，便于测试使用假云端连接，而不是假 Ollama。
