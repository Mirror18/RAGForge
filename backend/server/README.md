# RAGForge Server

Server 是 RAGForge 的模块化单体和业务真相进程，负责 `/api/v1`、认证与 Session、空间隔离、Provider/Prompt、检索、引用回答、SSE、审计、Outbox relay 和管理 API。批量解析与索引任务由独立的 `backend/ingestion-worker` 执行。

## 本地启动

从仓库根目录执行：

```powershell
python scripts/dev/core.py up
python scripts/dev/core.py health
mvn -f backend/pom.xml -pl server spring-boot:run
```

默认 HTTP 端口为 `8080`；一键本地闭环请使用 `scripts/dev/start-local.bat`，它会显式设置本地 PostgreSQL、RabbitMQ、Valkey、MinIO 和 Qdrant。Chat 与 Embedding 只走空间已授权的云端连接。健康检查：`http://127.0.0.1:8080/actuator/health`。

常用验证：

```powershell
mvn -f backend/pom.xml -q -pl server -am test
mvn -f backend/pom.xml -q -pl server -DskipTests compile
```

数据库迁移只由此进程的 Flyway 序列统一管理；Worker 不执行迁移。平台管理员三种入口（可并存，完成一次即可）：

1. **启动日志一次性码**：未配置 `RAGFORGE_BOOTSTRAP_ADMIN_TOKEN` 且库中尚无 `PLATFORM_ADMIN` 时，Server 在日志中打印 `STARTUP_BOOTSTRAP_CODE`（仅哈希入库）。
2. **CLI**：`RAGFORGE_ADMIN_CLI_PASSWORD` + `scripts/dev/create-platform-admin.bat --Email you@example.com`（无 HTTP）。
3. **dev profile**：`SPRING_PROFILES_ACTIVE=dev` 时自动 seed `dev-admin@ragforge.local`（见 `application-dev.yml`，勿用于生产）。

可选 break-glass：`RAGFORGE_BOOTSTRAP_ADMIN_TOKEN`（32+ 字符）。Provider key 等仍通过环境变量或 `.env.local` 注入。

## Ingestion persistence boundary

`V8__phase3_versioned_ingestion.sql` adds space-scoped source, revision,
artifact, parse-report, pipeline, job, attempt, step and checkpoint tables.
Versioned records are append-only and store only hashes, object URIs and
bounded metadata; source bytes and full extracted text stay in object storage.
Composite `(id, space_id)` foreign keys reject cross-space references.

The migration is forward-only in normal deployments. Before applying V8 take
the PostgreSQL backup described in [`运行与安全`](<../../backlog/docs/operations/doc-2 - 运行与安全.md>).
Rollback means restoring that backup and deploying the previous application,
not editing an already-applied migration. `IngestionRepository` repeats
`space_id` in read predicates and advances a checkpoint only after durable
revision, artifact, parse-report, active-pointer and outbox evidence is true.

## Phase 4 persistence boundary

`V9__phase4_chunk_index_profile.sql` adds space-scoped parent/child chunks,
chunk overrides, index versions, retrieval profiles and the single-row active
pointers. Chunks are immutable (update trigger rejects tampering); overrides
are append-only versions whose transitions follow
`ChunkOverrideTransitions` (`NONE -> ACTIVE -> NEEDS_REVIEW -> ACTIVE |
DISCARDED`); an index version becomes `ACTIVE` only after validation passed
and publishing switches the PostgreSQL active pointer atomically, keeping the
previous index retained for 24 hours. Vector data lives in Qdrant; PostgreSQL
holds the pointer and validation facts. Before applying V9 take the same
PostgreSQL backup required for V8.

计划基线：Java 21、Spring Boot 3.5.x、Spring AI 1.1.x、Maven、Flyway、PostgreSQL。

建议包结构：

```text
com.ragforge
├─ bootstrap
├─ identity
├─ space
├─ source
├─ ingestion
├─ provider
├─ prompt
├─ retrieval
├─ chat
├─ evaluation
├─ audit
└─ shared
```

每个业务模块内部使用 `domain`、`application`、`adapter.in`、`adapter.out`，但不为了目录美观创建空层。模块公共类型保持最小；Spring AI、JPA、Qdrant 或供应商 SDK 类型不穿透到 domain。

入口能力：REST `/api/v1`、SSE、Session/security、Outbox relay、online retrieval/chat、admin API。批量解析不在本进程执行。
