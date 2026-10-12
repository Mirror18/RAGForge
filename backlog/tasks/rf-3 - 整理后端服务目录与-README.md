---
id: RF-3
title: 整理后端服务目录与 README
status: Done
assignee: []
created_date: '2026-10-12 02:06'
updated_date: '2026-10-12 02:51'
labels:
  - backend docs
milestone: m-0
dependencies:
  - RF-2
priority: high
ordinal: 3000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
backend 增加 shared/gateway/space/knowledge/answer 说明目录；现有 server 与 worker 标明迁入目标。
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 backend/README.md 能画出四服务,每个目标目录有 README
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
backend/shared|gateway|space|knowledge|answer README 与 server/worker 迁入说明已落地；Maven 四进程拆分未做。
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
目标目录与 README 已对齐 ADR-0014。现有 server+worker 仍可运行。
<!-- SECTION:FINAL_SUMMARY:END -->
