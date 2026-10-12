---
id: RF-5
title: Spring Boot 对齐 Spring Cloud Alibaba 2025.0.0.0
status: Done
assignee: []
created_date: '2026-10-12 02:06'
updated_date: '2026-10-12 02:51'
labels:
  - backend
milestone: m-0
dependencies:
  - RF-2
priority: medium
ordinal: 5000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
根 POM Boot 3.5.0，引入 spring-cloud 与 spring-cloud-alibaba BOM。本轮不加 Nacos starter 到现有 server，避免无 Nacos 时无法启动。
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 pom.xml 声明 3.5.0 与 SCA 2025.0.0.0,现有安全版本覆盖仍在,现有模块仍可解析依赖
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
根 POM Boot 3.5.0 + SCA 2025.0.0.0；CVE override 保留；未把 Nacos 接到默认启动。
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
依赖 BOM 已对齐官方 SCA 2025.0.0.0 对应 Boot 3.5.0。Gateway/Nacos 后续卡片再加。
<!-- SECTION:FINAL_SUMMARY:END -->
