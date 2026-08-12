# OpenAPI

Phase 1 从最薄纵向切片开始建立 `ragforge-api-v1.yaml`。规范遵循 [API 与事件设计](../../docs/02-architecture/API_AND_EVENTS.md)：`/api/v1`、RFC 9457、UUIDv7、cursor、Idempotency-Key、Session/CSRF 和 service tokens。

没有实现和 contract test 的 endpoint 不标记为可用；实验接口使用明确标识，不污染稳定 v1。
