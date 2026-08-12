# 测试策略

## 1. 质量风险排序

最高风险不是普通 CRUD，而是：跨空间泄漏、引用伪造、未授权数据出境、半成品索引发布、消息重投重复副作用、模型/提示词变更造成静默质量退化，以及恢复后文档与向量不一致。

## 2. 测试层次

| 层次 | 目标 | 位置/工具方向 |
|---|---|---|
| Unit | 领域规则、解析纯函数、ranking、policy | 各应用模块内 |
| Architecture | 模块依赖、adapter 隔离 | ArchUnit / Modulith test |
| Contract | OpenAPI、事件、Provider、Connector、Tool | `tests/contract` |
| Integration | PostgreSQL/Qdrant/RabbitMQ/Valkey/S3 真实交互 | Testcontainers |
| E2E | 浏览器和 API 关键旅程 | `tests/e2e` |
| Evaluation | retrieval/generation/citation/abstention | `tests/evaluation` |
| Performance | 容量、延迟、稳定性、背压 | `tests/performance` |
| Security | 越权、SSRF、上传、prompt injection、secrets | `tests/security` |
| Recovery | 备份、重放、回滚、依赖故障 | Runbook drill |

## 3. 必测矩阵

### 3.1 权限与隔离

- 每个角色 × 每类资源 × read/write/admin。
- path `spaceId` 与资源真实空间不一致。
- Qdrant payload、缓存 key、对象 URI 和审计查询的空间过滤。
- 被移除成员的已有 Session、SSE、service token 行为。
- 未授权云 route、已关闭出境开关和 failover。

### 3.2 摄取

- add/modify/move/delete/unchanged。
- Windows `\` 与 Linux `/` 路径、Unicode、超长名称、符号链接和隐藏路径。
- parser 崩溃、OCR timeout、对象存储失败、向量部分写入、消息重投和 DLQ 重放。
- 同一 hash、不同 parser/pipeline/embedding 版本。
- active index 在构建失败时保持不变。

### 3.3 在线 Run

- model timeout、rate limit、context overflow、invalid JSON、连接中断。
- SSE 重连、重复 Last-Event-ID、取消与重试竞态。
- 模型生成未知 citation ID、重复引用和错误空间引用。
- tool 参数注入、网页重定向到私网、超限响应。
- usage provider report 与本地 estimate 的去重。

## 4. Testcontainers 策略

集成测试使用与生产兼容的 PostgreSQL、Qdrant、RabbitMQ、Valkey 和对象存储容器，不用内存数据库替代 SQL 语义。依赖版本与 Compose 基线统一。失败测试保存必要日志和 Trace，但不保存 Secret 或完整敏感文本。

## 5. CI 门槛

- PR：受影响测试全通过，无新增严重漏洞/秘密；覆盖率仅作趋势，不以单一比例代替风险测试。
- RAG 变更：关键指标不得低于主分支阈值；若有已接受 trade-off，必须链接 ADR/评审。
- Release：全量 E2E、evaluation、security 和 performance smoke 通过。
- 跨空间泄漏、未授权云出境、错误索引发布属于零容忍阻断项。

## 6. 缺陷严重度

- P0：数据泄漏/破坏、密钥泄露、不可恢复生产中断。
- P1：核心问答/摄取不可用、引用系统性错误、未经授权出境、无法回滚。
- P2：有替代路径的功能错误或明显性能退化。
- P3：低影响 UI、文档或边界体验问题。
