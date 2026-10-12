---
id: RF-19
title: BM25 停止全量 scroll
status: Done
assignee: []
created_date: '2026-10-12 16:50'
updated_date: '2026-10-12 16:50'
labels:
  - quality
  - backend
source: backlog
milestone: m-1
dependencies:
  - RF-18
priority: medium
ordinal: 19000
token_limit: 8000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
`DurableBm25CandidateStore` 每次 search 全量 scroll Qdrant。改为按 index version 持久化或缓存词法倒排，查询只读该版本。缓存键包含 `space_id` 与 index version。索引发布、回滚、退役时失效对应版本。用同一检索配置对比 scroll 与新实现的候选集合，摘要写入 `tests/evidence/`。
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 单次 search 不再对集合调用 points/scroll
- [x] #2 候选与 space、index version 隔离；其他空间文档不可命中
- [x] #3 评估摘要记录配置版本和候选差异
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. 在索引发布路径写入词法数据，search 路径只读。
2. 扩展 `DurableBm25CandidateStoreTest`，断言没有 scroll。
3. 保存评估摘要。
<!-- SECTION:PLAN:END -->
