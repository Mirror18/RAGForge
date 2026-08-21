# OpenAPI

[`ragforge-api-v1.yaml`](ragforge-api-v1.yaml) 是 RAGForge 的 REST/SSE v1 投影。文件使用 JSON 语法保存 YAML 合法内容，使当前 Python 标准库 contract tests 可以在不引入 YAML 依赖的情况下解析它。规范遵循 [API 与事件设计](../../docs/02-architecture/API_AND_EVENTS.md)：`/api/v1`、RFC 9457、UUIDv7、cursor、Idempotency-Key、Session/CSRF 和 service tokens。

## Contract 状态

顶层 `x-ragforge-contract-status=planned` 继续表示整个 API 尚未宣称实现。新增的 `x-ragforge-phase4-status=phase4-contract` 表示 P4-G 已冻结为 Phase 4 contract；它不表示整个 API 已实现。Phase 2、Phase 3 的 `x-ragforge-phase2-status`、`x-ragforge-phase3-status` 语义保留。P4-G 路径使用 `x-ragforge-implementation-status=phase4-contract`，供后端和前端以稳定投影实现。

## P4-G 路径

所有 P4-G endpoint 都严格包含 `/api/v1/spaces/{spaceId}/`。服务端必须以路径 `spaceId` 做授权与查询/变更隔离，不能接受请求体中的替代空间边界。

- `GET /api/v1/spaces/{spaceId}/chunk-studio/children/{childChunkId}`：读取单 child 的 parent-child 关系、provenance、citation anchor、vector/index status 和 override summary。
- `POST /api/v1/spaces/{spaceId}/chunk-studio/children/{childChunkId}/overrides`：创建手工 override。
- `POST /api/v1/spaces/{spaceId}/chunk-studio/children/{childChunkId}/overrides/{overrideId}/transitions`：执行 override 状态迁移。
- `POST /api/v1/spaces/{spaceId}/retrieval-playground/experiments`：提交一次只读检索实验，可选择 profile A 与可选 profile B 的精确版本。

每个 P4-G 操作均使用 `cookieAuth` 或 `serviceToken`。所有变更操作还要求 `X-CSRF-Token` 与 `Idempotency-Key`，并保留 `X-Correlation-Id` 链路字段。错误响应统一引用现有 `ProblemDetails` RFC 9457 schema。

## 数据最小化与安全边界

Chunk Studio 返回 `spaceId`、`documentRevisionId`、`childChunkId`、`contentRef`、`textHash` 以及结构化 provenance/anchor/vector status/override 字段，但不返回 `fullText`、`rawText` 或向量。创建 override 时客户端只能提交服务端管理的 `contentRef`、`textHash` 和 reason；`state`、`createdBy`、时间戳及其他审计字段由 repository/service 产生。状态机只允许 `ACTIVE -> NEEDS_REVIEW -> ACTIVE/DISCARDED`；`NONE` 是后端现有 repository 的创建语义，不是客户端迁移目标。

Retrieval Playground 返回原 query、normalized query、index version、profile A/B 精确版本，以及 dense、BM25、RRF、rerank、context、evidence、metrics 和 abstention 的结构化只读 trace。trace 只包含 chunk/revision 的引用、hash、rank、score、计数、耗时和 Citation/Evidence allow-list 项，不返回正文、向量、secret、credential 或自由引用文本。A/B 只能比较 candidate profile，响应中的 `activeProfileUnchanged=true` 是不改变 active pointer 的契约保证。

为兼容当前 P4-F，request schema 保留 `queryVector` seam；它是内部合成测试输入，`writeOnly`，不得进入响应、日志或审计记录，也不是公开客户端能力。

运行契约测试：

```text
python -m unittest discover -s tests/contract -p "test_*.py" -v
python scripts/ci/contract_test.py
```
