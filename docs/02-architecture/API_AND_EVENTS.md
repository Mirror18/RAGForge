# API 与事件设计规范

## 1. REST 基线

- Base path：`/api/v1`。
- JSON 字段、资源、数据库和事件使用英文。
- ID 使用 UUIDv7 字符串；时间使用 UTC ISO-8601。
- 错误采用 `application/problem+json`（RFC 9457）并增加稳定 `code`、`correlationId` 和安全的 field errors。
- 列表默认 cursor pagination；只对小型稳定字典使用 offset/page。
- 写入接口支持 `Idempotency-Key`，服务端校验同 key 的 request hash。
- 乐观并发使用 version/ETag，冲突返回 409/412。

## 2. 身份和浏览器安全

- 本地账号登录建立服务端 Session，Cookie 为 HttpOnly、Secure（非本地）、SameSite。
- 状态变更请求需要 CSRF token；CORS 使用显式 origin allowlist。
- service token 只用于非浏览器 API，使用 `Authorization: Bearer`，日志不得记录 token。
- 不采用长期 JWT 作为浏览器会话，确保服务端可立即吊销权限和 Session。

## 3. 资源示例

```text
POST   /api/v1/sessions
DELETE /api/v1/sessions/current
GET    /api/v1/spaces
POST   /api/v1/spaces
PUT    /api/v1/spaces/{spaceId}/members/{userId}
POST   /api/v1/spaces/{spaceId}/sources
POST   /api/v1/spaces/{spaceId}/sources/{sourceId}/syncs
GET    /api/v1/spaces/{spaceId}/ingestion-jobs/{jobId}
POST   /api/v1/spaces/{spaceId}/conversations
POST   /api/v1/spaces/{spaceId}/conversations/{conversationId}/runs
GET    /api/v1/spaces/{spaceId}/runs/{runId}/events
POST   /api/v1/spaces/{spaceId}/runs/{runId}/cancel
```

即使 path 含 `spaceId`，服务端仍从认证上下文校验成员关系，且校验 path 资源与数据库记录的空间一致。

## 4. SSE 协议

建议事件：

- `run.status`
- `step.status`
- `answer.delta`
- `citation.added`
- `usage.updated`
- `run.error`
- `run.completed`

每个事件包含 `id`、`sequence`、`runId`、`occurredAt`、`type` 和 versioned payload。重连携带 `Last-Event-ID`；事件保留窗口和超限后的快照恢复行为在契约中明确。

## 5. 领域/集成事件

事件 envelope：

```json
{
  "eventId": "uuid-v7",
  "eventType": "ingestion.job.requested.v1",
  "occurredAt": "2026-08-12T00:00:00Z",
  "producer": "ragforge-server",
  "correlationId": "uuid-v7",
  "causationId": "uuid-v7",
  "spaceId": "uuid-v7",
  "aggregateId": "uuid-v7",
  "payload": {}
}
```

规则：

- event type 末尾带 major schema version。
- 消费者忽略未知可选字段；破坏性变更新建 major type。
- payload 不放二进制、密钥、完整原文和无限制模型响应，只放 artifact reference。
- Schema 放在 `contracts/events/` 并做 producer/consumer contract tests。
- 事件至少一次投递，消费者必须幂等；不声称 RabbitMQ 提供 exactly-once。

## 6. OpenAPI 生命周期

OpenAPI 是对外 REST 契约的事实来源。CI 至少检查语法、lint、breaking change、生成客户端一致性和敏感字段示例。API 尚未实现前，`contracts/openapi/` 只保留规范和 README，不提交虚假可用接口。

## 7. 执行契约演进提案（Proposed）

[演进总设计](ARCHITECTURE_EVOLUTION.md)提出阶段 lineage、质量判定与检索执行快照；概念字段不等于已发布 API 或 event schema。本轮不改 `contracts`、不添加可用接口示例、不预先发布新事件名。人工接受 [ADR-0013](adr/0013-versioned-knowledge-execution.md)后，由单一契约 owner 定义字段与兼容性，落实 producer/consumer tests；破坏性事件变更新 major version，旧客户端不得把未知快照版本当成可重放版本。
