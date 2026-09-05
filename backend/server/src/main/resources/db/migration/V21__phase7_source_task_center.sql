-- P7C-01 task center lifecycle state. Existing rows are backfilled to ACTIVE
-- and version 0; all command writes lock the row (SELECT ... FOR UPDATE)
-- before applying the optimistic version predicate in the application.
ALTER TABLE sources
    ADD COLUMN IF NOT EXISTS lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS version_no INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

ALTER TABLE ingestion_jobs
    ADD COLUMN IF NOT EXISTS lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

ALTER TABLE index_versions
    ADD COLUMN IF NOT EXISTS lifecycle_state VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS lifecycle_version INTEGER NOT NULL DEFAULT 0;

-- The defaults above are the explicit backfill for pre-V21 rows. The unique
-- key is space-scoped so an idempotency key can never address another space.
CREATE TABLE source_task_actions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    resource_type VARCHAR(16) NOT NULL,
    resource_id UUID NOT NULL,
    operation VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    expected_version INTEGER NOT NULL,
    result_version INTEGER,
    reason VARCHAR(500),
    actor_user_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT source_task_actions_key_uq UNIQUE (space_id, idempotency_key),
    CONSTRAINT source_task_actions_resource_ck CHECK (resource_type IN ('SOURCE', 'JOB', 'INDEX')),
    CONSTRAINT source_task_actions_operation_ck CHECK (operation IN ('RETRY', 'REPLAY', 'RESYNC', 'ARCHIVE', 'DELETE')),
    CONSTRAINT source_task_actions_status_ck CHECK (status IN ('REQUESTED', 'ACCEPTED', 'ARCHIVED', 'DELETED')),
    CONSTRAINT source_task_actions_version_ck CHECK (expected_version >= 0 AND (result_version IS NULL OR result_version >= 0))
);

CREATE INDEX source_task_actions_space_time_idx
    ON source_task_actions (space_id, created_at DESC, id DESC);
CREATE INDEX source_task_actions_resource_idx
    ON source_task_actions (space_id, resource_type, resource_id, created_at DESC);

COMMENT ON TABLE source_task_actions IS
    'Durable task-center commands; handlers lock the target row before the optimistic version update.';
COMMENT ON COLUMN source_task_actions.request_hash IS
    'Hash of resource, operation, version and reason; duplicate keys with a different request are rejected.';
