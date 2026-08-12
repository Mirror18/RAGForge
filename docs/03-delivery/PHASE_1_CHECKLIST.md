# Phase 1 执行清单：工程与领域骨架

本清单把 [`ROADMAP.md`](ROADMAP.md) 的 Phase 1 退出条件拆成可执行证据。`[x]` 只表示已有可复核证据；“待外部 CI”不以文档声明替代运行结果。完整结果见 [`PHASE_1_IMPLEMENTATION_RESULTS.md`](../08-records/phase-1/PHASE_1_IMPLEMENTATION_RESULTS.md)。

## 1. 工程基线

- [x] Java 21 Maven 多模块可编译，server 与 ingestion-worker 生命周期分离；证据：`mvn -B -ntp -DskipTests compile`、`P1-ENG-001`。
- [x] Vue/TypeScript、Python AI Runtime 和统一开发命令可执行；证据：`npm ci`、`npm run format:check`、`npm run build`、`P1-ENG-001`。
- [x] README、`.env.example` 和 Compose 文档能够指导新环境启动；证据：[`deploy/compose/README.md`](../../deploy/compose/README.md)、`P1-OPS-001`。
- [x] OpenAPI、事件 Schema 和 contract tests 已先于消费者实现建立；证据：[`contracts/`](../../contracts/)、`python scripts/ci/contract_test.py`、`P1-CONTRACT-001`。

## 2. Core Compose 与运行检查

- [x] 独立 Compose project 可启动 PostgreSQL、Qdrant、RabbitMQ、Valkey、S3-compatible storage 和 Ollama 连接；证据：隔离项目 `ragforge-p1-api-check`、`P1-OPS-001`。
- [x] 端口、volume、network 和 health/readiness 检查可配置且不复用其他项目；证据：`validate_compose.py --project-name ragforge-p1-orch-check`、`P1-OPS-001`。生产资源限制尚未在 Phase 1 Compose 骨架中实现，列入 Phase 7。
- [x] server `/actuator/health` 和依赖健康检查可执行，错误路径不会泄漏 Secret；证据：`core.py health`、`/actuator/health` 返回 `UP`、secret scan、`P1-RECOVERY-001`。
- [x] PostgreSQL backup smoke 和 schema version 检查可执行；证据：`core.py backup-smoke`、Flyway V1/V2 查询、`P1-RECOVERY-001`。完整恢复前校验/恢复演练列入 Phase 6。

## 3. 身份、Session、CSRF 与空间 RBAC

- [x] 本地账号注册/登录/登出和服务端 Session 可运行，Cookie 属性符合 ADR-0007；证据：`ServerIntegrationTest`、API smoke、`P1-IAM-001`。
- [x] 状态变更请求需要 CSRF token；浏览器 Session 路径只使用 HttpOnly Cookie，不接受未定义的 Bearer fallback；证据：CSRF 403 用例、`AuthController`、`P1-IAM-001`。完整 service token 生命周期留至 Phase 2。
- [x] 创建空间、列出空间和成员变更按 Platform/Space role 授权；证据：`ServerIntegrationTest`、API smoke。
- [x] 跨空间读取、写入、成员变更集成测试通过，响应不泄漏另一空间内容；证据：`test_cross_space_membership_and_no_leak`、`ServerIntegrationTest`、`P1-IAM-001`。
- [x] `space_id` 在已实现的租户内容查询和 mutation 中强制存在并服务端校验；证据：空间 controller/service、Flyway FK、跨空间 no-leak 测试。未来 Qdrant/object/query 过滤仍是后续阶段。
- [x] audit event 和 correlation ID 可从请求追踪到关键身份/空间 mutation；证据：真实 PostgreSQL `audit_events` 查询、响应 correlation ID/UUIDv7 检查、`P1-DATA-001`。

## 4. 数据模型与可靠性骨架

- [x] Flyway migration 可在真实 PostgreSQL 执行，使用 UUID 类型和应用生成 UUIDv7；证据：Flyway V1/V2、schema query、`UuidV7Test`。
- [x] session、membership、audit、outbox 表约束、索引和空间边界有测试；证据：`ServerIntegrationTest`、真实 schema query。
- [x] Outbox 记录具有幂等键/事件 ID，未声称 exactly-once；失败路径可观察；证据：`outbox_events` 记录、event Schema、audit/log 契约。
- [x] RFC 9457 错误、cursor 分页、Idempotency-Key 和 optimistic version 基线有 contract/实现测试；证据：OpenAPI、contract tests、CSRF/409/no-leak API smoke。ETag 的完整资源实现留至后续领域切片。

## 5. CI 与质量门禁

- [ ] GitHub Actions 在空白业务骨架上的完整运行已通过；workflow 已定义格式、构建、单测、架构、contract、secret/dependency scan，但当前无 remote Run，且本机 Testcontainers 受 Docker npipe HTTP 400 阻塞；证据缺口：`P1-CI-001`。
- [x] CI 依赖缓存、SBOM 生成和失败路径已定义且不引用真实凭据；证据：`.github/workflows/quality.yml`、`dependency_inventory.py`、`DEPENDENCY_LEDGER.md`。SBOM/Grype 实际结果待 Linux Run。
- [x] 本地命令与 CI 命令一致，失败时退出码正确；证据：本地 Python/npm 门禁和失败过的 Testcontainers 命令均返回非零。
- [x] 迁移、健康、备份和跨空间安全测试证据已记录；证据：`P1-DATA-001`、`P1-RECOVERY-001`、`P1-IAM-001`。

## 6. 阶段评审与闭环

- [x] 新环境重复启动演练有记录，包含版本、镜像、端口和资源；证据：`PHASE_1_IMPLEMENTATION_RESULTS.md`、`P1-OPS-001`。
- [x] `PROJECT_STATUS.md`、`RISK_REGISTER.md`、`TRACEABILITY_MATRIX.md` 已更新；证据：本次阶段记录提交。
- [x] Phase 1 retrospective 记录事实、质量/安全数据、问题和 Phase 2 入口；证据：[`PHASE_1_RETROSPECTIVE.md`](../08-records/retrospectives/PHASE_1_RETROSPECTIVE.md)。
- [ ] 阶段正式关闭：CI 外部运行证据尚缺；未发现新的 P0/P1 代码缺陷，但必须在 CI 取证后重新审查风险并创建 phase-closure commit。
