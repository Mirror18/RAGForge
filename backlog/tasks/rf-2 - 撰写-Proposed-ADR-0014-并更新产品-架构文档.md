---
id: RF-2
title: 撰写 Proposed ADR-0014 并更新产品/架构文档
status: Done
assignee: []
created_date: '2026-10-12 02:06'
updated_date: '2026-10-12 02:51'
labels:
  - architecture docs
milestone: m-0
dependencies:
  - RF-1
priority: high
ordinal: 2000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
替代 ADR-0001 的进程划分；Boot 3.5.0；去掉 Ollama 产品路径。Accepted 前不拆运行进程。
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 ADR-0014 状态为 Proposed,docs/01-03 与风险追溯已同步,AGENTS.md 未擅自改为 Accepted
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
ADR-0014 Accepted 2026-10-12；ADR-0001 进程划分 superseded；Boot 3.5.0 + SCA 2025.0.0.0；ADR-0005 仅云端 OpenAI-compatible。
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
ADR-0014 已由项目所有者接受并写入 docs/07。产品路径改为 space/knowledge/answer/gateway 与云端-only 模型。
<!-- SECTION:FINAL_SUMMARY:END -->
