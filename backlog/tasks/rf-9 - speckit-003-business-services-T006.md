---
id: RF-9
title: 接入 Nacos 与 gateway；浏览器只访问 gateway
status: Done
assignee: []
created_date: '2026-10-12 16:19'
updated_date: '2026-10-14 10:00'
labels:
  - speckit
source: speckit
source_spec: 003-business-services
source_task: T006
milestone: m-2
dependencies: []
priority: low
ordinal: 9000
token_limit: 8000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
来自 `specs/003-business-services/tasks.md` 的 T006。

接入 Nacos 与 gateway；浏览器只访问 gateway
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 T006 按规格完成，并在 `specs/003-business-services/tasks.md` 勾选一致
<!-- AC:END -->

## Notes

- `backend/gateway` 独立进程；默认静态上游，`-Pnacos` + profile `nacos` 才启用发现
- Vite / nginx / Compose：浏览器只到 gateway；server 不对外映射端口
- 本地 `start-local` / `restart-local` 拉起 Gateway `25080`，Web 代理到 gateway
