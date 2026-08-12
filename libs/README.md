# Shared Libraries

共享库仅服务真实跨应用复用，不能成为放置杂项的 `common` 大包。

- `java/`：事件 envelope、稳定 ID/error/observability contracts、Worker ports。
- `typescript/`：由 OpenAPI 生成的 API client 和少量共享 UI contracts。
- `python/`：AI Runtime 的内部 schema/client，优先从 contracts 生成。

业务聚合、JPA entity、Spring bean 和供应商 SDK 不放共享库。新增共享库需说明 owner、消费者、兼容政策和发布方式。
