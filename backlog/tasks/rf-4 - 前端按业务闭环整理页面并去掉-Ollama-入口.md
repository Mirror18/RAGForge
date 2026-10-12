---
id: RF-4
title: 前端按业务闭环整理页面并去掉 Ollama 入口
status: Done
assignee: []
created_date: '2026-10-12 02:06'
updated_date: '2026-10-12 02:51'
labels:
  - frontend
milestone: m-0
dependencies:
  - RF-2
priority: high
ordinal: 4000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
视图分目录、一套样式、侧栏按空间-模型-资料-问答-管理；Chat 只走云端。
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 views 分目录,App.vue 只有一套样式来源,页面无本地/云端双按钮,E2E 选择器更新
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
前端 views 按闭环整理，去掉 Ollama 入口；tsc/vitest 已通过。
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
页面按空间-模型-资料-问答-管理收拢，Chat 只走云端。
<!-- SECTION:FINAL_SUMMARY:END -->
