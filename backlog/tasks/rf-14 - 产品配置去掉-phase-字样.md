---
id: RF-14
title: 产品配置去掉 phase 字样
status: To Do
assignee: []
created_date: '2026-10-12 16:20'
updated_date: '2026-10-12 16:50'
labels:
  - quality
source: backlog
milestone: m-1
dependencies: []
priority: medium
ordinal: 21000
token_limit: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
新部署的对象前缀、operations 开关和页面文案使用产品名。`application.yml` 仍接受旧环境变量一个版本。类名、已应用 Flyway 文件和历史证据路径保持不变。
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 默认对象前缀不再是 `phase3`，旧 `S3_PREFIX` 仍可覆盖
- [ ] #2 README、启动脚本和页面不再把 phase3/5/6 写成使用者步骤
- [ ] #3 已有对象键不被迁移脚本改写
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
1. 改 server/worker 默认配置与 Compose 说明，保留旧变量别名。
2. 搜页面与 README 中面向使用者的 phase 字样并替换。
<!-- SECTION:PLAN:END -->
