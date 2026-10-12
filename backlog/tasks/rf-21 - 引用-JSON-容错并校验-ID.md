---
id: RF-21
title: 引用 JSON 容错并校验 ID
status: To Do
assignee: []
created_date: '2026-10-12 16:50'
updated_date: '2026-10-12 16:50'
labels:
  - quality
  - backend
source: backlog
milestone: m-1
dependencies:
  - RF-11
priority: low
ordinal: 23000
token_limit: 6000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
生成结果仍必须能映射到当前 Evidence Bundle 的 evidence ID。解析器额外接受一种文档化的等价 JSON（例如 claims 内的 `evidence_id`），拒绝自由文本引用和 bundle 外 ID。失败原因保持 `citation_invalid`，供 RF-12 显示下一步。
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 两种已文档化 JSON 都能投影出同一条结构化 citation
- [ ] #2 自由文本、空 claims、bundle 外 ID 仍拒答
- [ ] #3 契约或解析测试覆盖这三类输入
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. 只扩展 `CitationTokenParser` 或生成结果解析，不改 provenance 字段。
2. 在 OpenAPI 或 prompt 契约注释中写明两种形状。
<!-- SECTION:PLAN:END -->
