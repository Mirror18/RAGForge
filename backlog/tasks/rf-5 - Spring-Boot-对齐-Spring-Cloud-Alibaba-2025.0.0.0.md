---
id: RF-5
title: Spring Boot 对齐 Spring Cloud Alibaba 2025.0.0.0
status: In Progress
assignee: []
created_date: '2026-10-12 02:06'
updated_date: '2026-10-12 02:13'
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
- [ ] #1 pom.xml 声明 3.5.0 与 SCA 2025.0.0.0,现有安全版本覆盖仍在,现有模块仍可解析依赖
<!-- AC:END -->
