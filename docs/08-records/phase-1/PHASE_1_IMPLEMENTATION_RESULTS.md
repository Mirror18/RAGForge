# Phase 1 实施与验收结果

- 验收日期：2026-08-12
- 主分支基线：`c9b3d6c5880c58e36e6e8c3af2914502118c9cbf`
- 当前阶段集成头：`ffdb0d5`（Testcontainers 1.21.4 兼容性修正后）
- 当前主分支：`main`
- 运行环境：Windows 11、Java 21.0.7、Maven 3.9.6、Node 22.22.2、npm 10.9.7、Python 3.12.13、Docker 29.6.1、Compose v5.3.0
- 本阶段未配置 GitHub remote，未提交或推送真实凭据。

## 1. 阶段范围与合并记录

本阶段只交付工程/领域骨架、契约、身份空间边界、数据库可靠性基础、core Compose 和质量门禁；Provider、摄取、检索、引用回答和完整 service-token 生命周期留到后续阶段。

已审查并合入的任务提交：

| 提交 | 内容 |
|---|---|
| `532833a07244ed66390b8ee8ab4546c4fa73b798` | OpenAPI 与事件 Schema 基线 |
| `936269bef9ee6f12c89776c59f1b4e2656abe406` | 匿名认证 CSRF 契约修正 |
| `45ab6b411b9483a60ef350f1610e4cbfcdf7ea09` | core Compose 与 CI 门禁 |
| `0b97d1e16c2f155efd88a104f24cb406ea06c4ca` | Markdown 生成目录扫描边界修正 |
| `82dfd74c189ba378d65f1767e3aa04cc50746e35` | 服务端身份、空间、Session 与迁移 |
| `e60ff32598ba443627875d383b683d56ea1fed17` | 禁用默认生成开发密码 |
| `6b96bd1bae8758e2b97ef9c129df5e34bc611cad` | 固定 Testcontainers 镜像版本 |

主分支保留了对应的 `--no-ff` 合并提交；阶段闭环提交必须在本记录和清单审查完成后创建。

## 2. ROADMAP 退出条件证据

| 退出条件 | 状态 | 可复核证据 |
|---|---|---|
| 新环境按文档可重复启动 | 已满足（本地） | [`deploy/compose/README.md`](../../05-operations/DEPLOYMENT.md)；`python scripts/ci/validate_compose.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example`；隔离项目 `ragforge-p1-api-check` 实际启动 PostgreSQL、Qdrant、RabbitMQ、Valkey、MinIO，并使用独立 network、volume 和端口 block |
| 跨空间授权集成测试通过 | 已满足（本地黑盒 + Java 集成测试） | [`tests/acceptance/test_phase1_api_smoke.py`](../../../tests/acceptance/test_phase1_api_smoke.py) 3/3；[`ServerIntegrationTest.java`](../../../apps/server/src/test/java/com/ragforge/server/ServerIntegrationTest.java) 覆盖非成员读写/成员变更、CSRF、Session、幂等和迁移；真实 Compose API 黑盒通过 |
| 数据库迁移、备份冒烟和健康检查可执行 | 已满足（真实 PostgreSQL/依赖） | Flyway `V1__initial_schema.sql`、`V2__idempotency_records.sql`；`python scripts/dev/core.py --project-name ragforge-p1-api-check health`；`python scripts/dev/core.py --project-name ragforge-p1-api-check backup-smoke --output tmp/backups/phase1-api-check.sql`；`/actuator/health` 返回 `UP`；备份文件生成并有 SHA-256 记录。完整恢复演练留至 Phase 6 |
| CI 对空白业务骨架全部通过 | 待外部 CI 运行取证 | `.github/workflows/quality.yml` 已覆盖格式、Compose、架构、链接、秘密、Phase 0、contract、依赖、SBOM、Maven 和 npm；本地 Python/Compose/契约/前端门禁以及全量 `mvn test` 已通过。当前仓库没有 remote，故不能把未执行的 Linux GitHub Run 或 SBOM/Grype artifact 标为通过 |

### 2.1 真实运行配置

本轮 API 黑盒使用的隔离项目为 `ragforge-p1-api-check`，只包含合成测试数据。关键版本为：PostgreSQL `16.4-alpine`、Qdrant `v1.11.5`、RabbitMQ `3.13-management-alpine`、Valkey `8.0.1-alpine`、MinIO `RELEASE.2024-12-18T13-15-44Z`、Ollama `0.5.4`。Ollama 只作为宿主机连接探针，未启用云端 fallback。

已观察到的安全和数据证据：

- 登录返回 HttpOnly、Lax、可配置 Secure 的 `RAGFORGE_SESSION` Cookie；浏览器 mutation 需要 `X-CSRF-Token` 和 `Idempotency-Key`。
- Session 活跃认证从 Valkey 读取；删除 Valkey session key 后请求变为 401，而 PostgreSQL session 元数据未被错误撤销。
- `space_id` 通过数据库外键、membership 查询和服务端授权路径强制；非成员访问返回不泄漏空间名称的 `SPACE_NOT_FOUND`。
- 创建空间同时写入 `audit_events` 和 `outbox_events`；Outbox 使用事件 ID/幂等约束，没有声称 exactly-once。
- UUID 由应用生成 UUIDv7；响应中的资源 ID 和 correlation ID 已检查版本位。
- 服务重启日志不再出现 Spring Security 默认生成密码；默认开发密码未写入日志。

## 3. 验证结果与限制

通过的仓库级命令：

```text
python scripts/ci/format_check.py
python scripts/ci/check_markdown_links.py
python -m unittest discover -s scripts/ci -p "test_*.py" -v
python scripts/ci/secret_scan.py
python scripts/ci/architecture_check.py
python scripts/ci/contract_test.py
python -m unittest discover -s tests/contract -p "test_*.py" -v
python scripts/ci/dependency_inventory.py --require-lockfiles
python scripts/ci/validate_compose.py --project-name ragforge-p1-orch-check --env-file deploy/compose/env.example
python -m unittest discover -s scripts/phase0 -p "test_*.py" -v
python tests/acceptance/test_phase1_api_smoke.py
mvn -B -ntp -DskipTests compile
mvn -B -ntp -pl apps/server "-Dtest=UuidV7Test,SecurityConfigTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -B -ntp -pl apps/ingestion-worker test
npm ci
npm run format:check
npm run build
npm audit --audit-level=high
```

全量 `mvn -B -ntp test` 已真实执行并通过：8 个测试、0 failures/errors，其中 `ServerIntegrationTest` 的 5 个用例使用 PostgreSQL `16.4-alpine` 和 Valkey `8.0.1-alpine` 真实容器。此前 Testcontainers 1.21.3 在 Docker Engine 29.6.1 上的 npipe HTTP 400 已通过升级 1.21.4 并显式管理测试容器生命周期解决；修正提交为 `545d75d`，合并提交为 `ffdb0d5`。

## 4. 未完成项与阶段入口

Phase 1 的业务骨架已可供 Phase 2 使用，但阶段正式关闭前必须取得一次全新的 Linux CI Run，确认 SBOM/Grype action 和 npm/Maven job 的完整路径。没有 remote 的原因属于外部协调/仓库权限问题，不通过勾选文档掩盖。

Phase 2 入口：Provider Registry、Model Profile/Route/Space Binding、Prompt Version、Run/Step/SSE 和显式云端出境策略。当前实现不宣称已完成 service token 管理、RAG、引用验证、摄取或检索能力。
