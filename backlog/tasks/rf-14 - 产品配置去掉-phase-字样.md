---
id: RF-14
title: 产品配置去掉 phase 字样
status: To Do
assignee: []
created_date: '2026-10-12 16:20'
updated_date: '2026-10-12 16:20'
labels:
  - quality
  - docs
source: backlog
milestone: m-1
dependencies: []
priority: medium
ordinal: 14000
token_limit: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
对外配置、对象前缀、前端文案不再使用 phase3/phase5/phase6。类名和历史迁移可以保留，但 `application.yml` 默认前缀、Compose 说明和页面文案改成产品语言。
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 新空间默认对象前缀与 operations 开关使用产品名
- [ ] #2 页面与 README 不再把 phase 当作使用者需要理解的步骤
<!-- AC:END -->
