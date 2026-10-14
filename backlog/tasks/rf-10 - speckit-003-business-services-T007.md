---
id: RF-10
title: 删除独立 `backend/ai-runtime` 部署面，能力并入 knowledge/answer infrastructure
status: Done
assignee: []
created_date: '2026-10-12 16:19'
updated_date: '2026-10-14 10:45'
labels:
  - speckit
source: speckit
source_spec: 003-business-services
source_task: T007
milestone: m-2
dependencies: []
priority: low
ordinal: 10000
token_limit: 8000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
来自 `specs/003-business-services/tasks.md` 的 T007。

删除独立 `backend/ai-runtime` 部署面，能力并入 knowledge/answer infrastructure
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 T007 按规格完成，并在 `specs/003-business-services/tasks.md` 勾选一致
<!-- AC:END -->

## Notes

- 代码迁至 `backend/knowledge/ai-runtime`；Compose 本无独立服务
- 架构门禁改为要求 knowledge 下路径，不再把 `ai-runtime` 当业务服务名
