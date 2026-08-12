# OpenAPI

Phase 1 从最薄纵向切片开始建立 [`ragforge-api-v1.yaml`](ragforge-api-v1.yaml)。文件使用 JSON 语法保存 YAML 合法内容，使当前 Python 标准库测试可以在不引入 YAML 依赖的情况下解析它。规范遵循 [API 与事件设计](../../docs/02-architecture/API_AND_EVENTS.md)：`/api/v1`、RFC 9457、UUIDv7、cursor、Idempotency-Key、Session/CSRF 和 service tokens。Phase 3 增加 space-scoped sources、sync jobs/status、documents 和 immutable revisions；这些 projection 不返回 Secret、`credentialRef`、二进制或完整文档文本。

本文件是 planned contract，不声明任何 endpoint 已实现或可用。`/auth/login` 和 `/sessions` 都表达 session creation：前者是显式 auth facade，后者是架构文档中的 canonical session operation；实现阶段必须统一行为并保持相同安全约束。Phase 3 合同状态由顶层 `x-ragforge-phase3-status=phase3-contract` 和路径上的 `x-ragforge-implementation-status=phase3-contract` 表示。没有实现和 contract test 的 endpoint 不标记为可用；实验接口使用明确标识，不污染稳定 v1。

Sources、documents、revisions、jobs 的查询都要求 `spaceId` 路径隔离和 `X-Correlation-Id`。所有 mutation 还要求 `Idempotency-Key`、浏览器 session 的 `X-CSRF-Token`，并对版本化更新使用 `If-Match`。具体 SourceConnector、checkpoint、artifact、parse report 和 Outbox/worker 语义见 [`../ingestion/README.md`](../ingestion/README.md)。

运行契约测试：

```text
python -m unittest discover -s tests/contract -p "test_*.py" -v
```
