# ADR-0004：空间级 RBAC

- Status: Accepted
- Date: 2026-08-12

## Context

首版面向单个企业内部部署，需要多人和多个知识域，但文档级 ACL 会显著增加同步、索引过滤、缓存和测试复杂度。

## Decision

采用单租户、多知识空间。平台角色为 Platform Admin；空间角色为 Space Admin、Editor、Viewer。会话严格绑定一个空间，首版不支持跨空间检索或文档级 ACL。

## Consequences

- 权限模型清晰，可完整测试无跨空间泄漏。
- 用户需要通过拆分空间表达不同可见范围。
- 未来增加文档 ACL 必须新建 ADR，并重做索引、缓存、引用和评估安全设计。
