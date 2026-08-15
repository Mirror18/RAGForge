# RAGForge Server

## Phase 3 persistence boundary

`V8__phase3_versioned_ingestion.sql` adds space-scoped source, revision,
artifact, parse-report, pipeline, job, attempt, step and checkpoint tables.
Versioned records are append-only and store only hashes, object URIs and
bounded metadata; source bytes and full extracted text stay in object storage.
Composite `(id, space_id)` foreign keys reject cross-space references.

The migration is forward-only in normal deployments. Before applying V8 take
the PostgreSQL backup required by [`BACKUP_RESTORE.md`](../../docs/05-operations/BACKUP_RESTORE.md).
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
