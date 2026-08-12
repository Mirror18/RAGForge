-- Phase 3 versioned ingestion persistence.
--
-- All content-bearing rows are space scoped.  Raw bytes and extracted text live
-- in object storage; PostgreSQL stores only immutable references, hashes and
-- bounded reports.  The migration is append-only after V7.

ALTER TABLE outbox_events
    ADD COLUMN causation_id UUID,
    ADD COLUMN delivery_attempts INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN dead_lettered_at TIMESTAMPTZ,
    ADD CONSTRAINT outbox_events_delivery_attempts_ck CHECK (delivery_attempts >= 0);

CREATE INDEX outbox_events_delivery_due_idx
    ON outbox_events (next_attempt_at, occurred_at)
    WHERE published_at IS NULL AND dead_lettered_at IS NULL;

CREATE TABLE sources (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT sources_identity_uq UNIQUE (id, space_id)
);

CREATE TABLE source_versions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    connector_type VARCHAR(40) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    source_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    read_only BOOLEAN NOT NULL DEFAULT TRUE,
    root_ref VARCHAR(512) NOT NULL,
    include_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    exclude_rules JSONB NOT NULL DEFAULT '[]'::jsonb,
    credential_configured BOOLEAN NOT NULL DEFAULT FALSE,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT source_versions_version_ck CHECK (version_no > 0),
    CONSTRAINT source_versions_read_only_ck CHECK (read_only = TRUE),
    CONSTRAINT source_versions_connector_ck CHECK (connector_type IN ('FILESYSTEM', 'LOCAL_DIRECTORY', 'GIT', 'OBSIDIAN_VAULT')),
    CONSTRAINT source_versions_state_ck CHECK (source_state IN ('ACTIVE', 'PAUSED', 'ERROR')),
    CONSTRAINT source_versions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT source_versions_key_uq UNIQUE (space_id, source_id, version_no),
    CONSTRAINT source_versions_source_fk FOREIGN KEY (source_id, space_id)
        REFERENCES sources (id, space_id) ON DELETE CASCADE
);

CREATE INDEX source_versions_space_created_idx ON source_versions (space_id, created_at DESC);
CREATE INDEX source_versions_space_source_idx ON source_versions (space_id, source_id, version_no DESC);

CREATE TABLE source_checkpoints (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    source_version_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    cursor_type VARCHAR(32) NOT NULL,
    cursor_value VARCHAR(512),
    last_successful_changeset_id UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT source_checkpoints_version_ck CHECK (version_no > 0),
    CONSTRAINT source_checkpoints_cursor_type_ck CHECK (cursor_type IN ('NONE', 'FILESYSTEM_SCAN', 'GIT_COMMIT', 'REMOTE_ETAG', 'CONNECTOR_CURSOR')),
    CONSTRAINT source_checkpoints_identity_uq UNIQUE (id, space_id),
    CONSTRAINT source_checkpoints_source_uq UNIQUE (space_id, source_id),
    CONSTRAINT source_checkpoints_source_identity_fk FOREIGN KEY (source_id, space_id)
        REFERENCES sources (id, space_id) ON DELETE CASCADE,
    CONSTRAINT source_checkpoints_source_fk FOREIGN KEY (source_version_id, space_id)
        REFERENCES source_versions (id, space_id) ON DELETE RESTRICT
);

CREATE TABLE source_documents (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    stable_source_object_id VARCHAR(512) NOT NULL,
    canonical_source_path VARCHAR(2048) NOT NULL,
    basename VARCHAR(255) NOT NULL,
    version_no INTEGER NOT NULL,
    current_state VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    active_revision_id UUID,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT source_documents_version_ck CHECK (version_no > 0),
    CONSTRAINT source_documents_state_ck CHECK (current_state IN ('ACTIVE', 'DELETED')),
    CONSTRAINT source_documents_path_ck CHECK (
        canonical_source_path !~ '^/'
        AND canonical_source_path !~ '^[A-Za-z]:'
        AND canonical_source_path !~ E'\\\\'
        AND canonical_source_path !~ '(^|/)\\.\\.?(/|$)'
        AND canonical_source_path !~ E'\\x00'
    ),
    CONSTRAINT source_documents_identity_uq UNIQUE (id, space_id),
    CONSTRAINT source_documents_object_uq UNIQUE (space_id, source_id, stable_source_object_id),
    CONSTRAINT source_documents_source_fk FOREIGN KEY (source_id, space_id)
        REFERENCES sources (id, space_id) ON DELETE CASCADE
);

CREATE INDEX source_documents_space_path_idx ON source_documents (space_id, canonical_source_path);

CREATE TABLE pipeline_versions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    pipeline_name VARCHAR(120) NOT NULL,
    parser_name VARCHAR(120) NOT NULL,
    parser_version VARCHAR(120) NOT NULL,
    configuration_hash CHAR(64) NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pipeline_versions_version_ck CHECK (version_no > 0),
    CONSTRAINT pipeline_versions_hash_ck CHECK (configuration_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT pipeline_versions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT pipeline_versions_key_uq UNIQUE (space_id, pipeline_name, version_no)
);

CREATE TABLE artifacts (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    source_document_id UUID,
    document_revision_id UUID,
    version_no INTEGER NOT NULL,
    artifact_kind VARCHAR(32) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    byte_length BIGINT NOT NULL,
    sha256 CHAR(64) NOT NULL,
    storage_uri VARCHAR(1024) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    immutable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT artifacts_version_ck CHECK (version_no > 0),
    CONSTRAINT artifacts_size_ck CHECK (byte_length >= 0 AND byte_length <= 10737418240),
    CONSTRAINT artifacts_hash_ck CHECK (sha256 ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT artifacts_kind_ck CHECK (artifact_kind IN ('SOURCE_BYTES', 'PARSED_TEXT', 'PARSE_REPORT', 'CHUNKS')),
    CONSTRAINT artifacts_immutable_ck CHECK (immutable = TRUE),
    CONSTRAINT artifacts_identity_uq UNIQUE (id, space_id),
    CONSTRAINT artifacts_storage_uq UNIQUE (space_id, storage_uri)
);

CREATE TABLE document_revisions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    source_document_id UUID NOT NULL,
    revision_no INTEGER NOT NULL,
    source_version VARCHAR(512) NOT NULL,
    canonical_source_path VARCHAR(2048) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    source_artifact_id UUID,
    parse_report_id UUID,
    revision_state VARCHAR(32) NOT NULL DEFAULT 'DISCOVERED',
    immutable BOOLEAN NOT NULL DEFAULT TRUE,
    git_commit_sha CHAR(40),
    discovered_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT document_revisions_revision_ck CHECK (revision_no > 0),
    CONSTRAINT document_revisions_hash_ck CHECK (content_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT document_revisions_git_sha_ck CHECK (git_commit_sha IS NULL OR git_commit_sha ~ '^[0-9a-fA-F]{40}$'),
    CONSTRAINT document_revisions_state_ck CHECK (revision_state IN ('DISCOVERED', 'FETCHED', 'PARSED', 'FAILED', 'DELETED')),
    CONSTRAINT document_revisions_immutable_ck CHECK (immutable = TRUE),
    CONSTRAINT document_revisions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT document_revisions_key_uq UNIQUE (space_id, source_document_id, revision_no),
    CONSTRAINT document_revisions_document_fk FOREIGN KEY (source_document_id, space_id)
        REFERENCES source_documents (id, space_id) ON DELETE RESTRICT
);

CREATE INDEX document_revisions_space_document_idx
    ON document_revisions (space_id, source_document_id, revision_no DESC);

CREATE TABLE parse_reports (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    document_revision_id UUID NOT NULL,
    source_artifact_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    page_count INTEGER NOT NULL DEFAULT 0,
    character_count BIGINT NOT NULL DEFAULT 0,
    token_count BIGINT NOT NULL DEFAULT 0,
    native_page_count INTEGER NOT NULL DEFAULT 0,
    ocr_page_count INTEGER NOT NULL DEFAULT 0,
    parser_name VARCHAR(120) NOT NULL,
    parser_version VARCHAR(120) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    errors JSONB NOT NULL DEFAULT '[]'::jsonb,
    extracted_text_artifact_id UUID,
    ocr_status VARCHAR(32) NOT NULL DEFAULT 'NOT_REQUESTED',
    ocr_engine VARCHAR(120),
    ocr_engine_version VARCHAR(120),
    ocr_trigger_reason VARCHAR(32) NOT NULL DEFAULT 'NONE',
    ocr_audit_state VARCHAR(32) NOT NULL DEFAULT 'NOT_APPLICABLE',
    immutable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT parse_reports_version_ck CHECK (version_no > 0),
    CONSTRAINT parse_reports_counts_ck CHECK (page_count >= 0 AND character_count >= 0 AND token_count >= 0 AND native_page_count >= 0 AND ocr_page_count >= 0 AND duration_ms >= 0),
    CONSTRAINT parse_reports_status_ck CHECK (status IN ('SUCCEEDED', 'FAILED', 'OCR_REQUIRED', 'OCR_UNAVAILABLE', 'BLOCKED')),
    CONSTRAINT parse_reports_ocr_status_ck CHECK (ocr_status IN ('NOT_REQUESTED', 'SUCCEEDED', 'UNAVAILABLE', 'FAILED')),
    CONSTRAINT parse_reports_ocr_trigger_ck CHECK (ocr_trigger_reason IN ('NONE', 'IMAGE_ONLY_PDF', 'LOW_TEXT_QUALITY', 'SCANNED_PAGE', 'PARSER_FAILURE')),
    CONSTRAINT parse_reports_ocr_audit_ck CHECK (ocr_audit_state IN ('NOT_APPLICABLE', 'PENDING', 'COMPLETED', 'BLOCKED')),
    CONSTRAINT parse_reports_immutable_ck CHECK (immutable = TRUE),
    CONSTRAINT parse_reports_identity_uq UNIQUE (id, space_id),
    CONSTRAINT parse_reports_key_uq UNIQUE (space_id, document_revision_id, version_no)
);

CREATE TABLE active_document_pointers (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    source_document_id UUID NOT NULL,
    active_revision_id UUID,
    version_no INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT active_document_pointers_version_ck CHECK (version_no > 0),
    CONSTRAINT active_document_pointers_identity_uq UNIQUE (id, space_id),
    CONSTRAINT active_document_pointers_document_uq UNIQUE (space_id, source_document_id),
    CONSTRAINT active_document_pointers_document_fk FOREIGN KEY (source_document_id, space_id)
        REFERENCES source_documents (id, space_id) ON DELETE CASCADE
);

CREATE TABLE ingestion_jobs (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    source_document_id UUID,
    document_revision_id UUID,
    pipeline_version_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'REQUESTED',
    idempotency_key VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    causation_id UUID,
    version_no INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ingestion_jobs_status_ck CHECK (status IN ('REQUESTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'RETRY_SCHEDULED', 'DLQ')),
    CONSTRAINT ingestion_jobs_version_ck CHECK (version_no > 0),
    CONSTRAINT ingestion_jobs_idempotency_ck CHECK (idempotency_key ~ '^[A-Za-z0-9._~-]+$'),
    CONSTRAINT ingestion_jobs_identity_uq UNIQUE (id, space_id),
    CONSTRAINT ingestion_jobs_idempotency_uq UNIQUE (space_id, source_id, idempotency_key),
    CONSTRAINT ingestion_jobs_source_fk FOREIGN KEY (source_id, space_id)
        REFERENCES sources (id, space_id) ON DELETE CASCADE,
    CONSTRAINT ingestion_jobs_pipeline_fk FOREIGN KEY (pipeline_version_id, space_id)
        REFERENCES pipeline_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT ingestion_jobs_document_fk FOREIGN KEY (source_document_id, space_id)
        REFERENCES source_documents (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT ingestion_jobs_revision_fk FOREIGN KEY (document_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT
);

CREATE TABLE ingestion_job_attempts (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    job_id UUID NOT NULL,
    attempt_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    idempotency_key VARCHAR(255) NOT NULL,
    correlation_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT ingestion_job_attempts_no_ck CHECK (attempt_no BETWEEN 1 AND 20),
    CONSTRAINT ingestion_job_attempts_status_ck CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'RETRY_SCHEDULED', 'DLQ')),
    CONSTRAINT ingestion_job_attempts_identity_uq UNIQUE (id, space_id),
    CONSTRAINT ingestion_job_attempts_key_uq UNIQUE (space_id, job_id, attempt_no),
    CONSTRAINT ingestion_job_attempts_job_fk FOREIGN KEY (job_id, space_id)
        REFERENCES ingestion_jobs (id, space_id) ON DELETE CASCADE
);

CREATE TABLE pipeline_step_executions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    job_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    step_name VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RUNNING',
    attempt_no INTEGER NOT NULL,
    input_artifact_id UUID,
    output_artifact_id UUID,
    parse_report_id UUID,
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    error_code VARCHAR(64),
    started_at TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    CONSTRAINT pipeline_step_executions_step_ck CHECK (step_name IN ('DISCOVER', 'FETCH', 'PARSE', 'OCR', 'PERSIST', 'PUBLISH')),
    CONSTRAINT pipeline_step_executions_status_ck CHECK (status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    CONSTRAINT pipeline_step_executions_attempt_ck CHECK (attempt_no BETWEEN 1 AND 20),
    CONSTRAINT pipeline_step_executions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT pipeline_step_executions_key_uq UNIQUE (space_id, job_id, attempt_id, step_name, attempt_no),
    CONSTRAINT pipeline_step_executions_job_fk FOREIGN KEY (job_id, space_id)
        REFERENCES ingestion_jobs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT pipeline_step_executions_attempt_fk FOREIGN KEY (attempt_id, space_id)
        REFERENCES ingestion_job_attempts (id, space_id) ON DELETE CASCADE,
    CONSTRAINT pipeline_step_executions_input_fk FOREIGN KEY (input_artifact_id, space_id)
        REFERENCES artifacts (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT pipeline_step_executions_output_fk FOREIGN KEY (output_artifact_id, space_id)
        REFERENCES artifacts (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT pipeline_step_executions_report_fk FOREIGN KEY (parse_report_id, space_id)
        REFERENCES parse_reports (id, space_id) ON DELETE RESTRICT
);

CREATE TABLE ingestion_idempotency (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    job_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    step_name VARCHAR(32) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    result_reference UUID,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ingestion_idempotency_identity_uq UNIQUE (id, space_id),
    CONSTRAINT ingestion_idempotency_key_uq UNIQUE (space_id, job_id, attempt_id, step_name, idempotency_key),
    CONSTRAINT ingestion_idempotency_job_fk FOREIGN KEY (job_id, space_id)
        REFERENCES ingestion_jobs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT ingestion_idempotency_attempt_fk FOREIGN KEY (attempt_id, space_id)
        REFERENCES ingestion_job_attempts (id, space_id) ON DELETE CASCADE
);

ALTER TABLE document_revisions
    ADD CONSTRAINT document_revisions_source_artifact_fk FOREIGN KEY (source_artifact_id, space_id)
        REFERENCES artifacts (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT document_revisions_parse_report_fk FOREIGN KEY (parse_report_id, space_id)
        REFERENCES parse_reports (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE artifacts
    ADD CONSTRAINT artifacts_document_fk FOREIGN KEY (source_document_id, space_id)
        REFERENCES source_documents (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT artifacts_revision_fk FOREIGN KEY (document_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE parse_reports
    ADD CONSTRAINT parse_reports_revision_fk FOREIGN KEY (document_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT parse_reports_source_artifact_fk FOREIGN KEY (source_artifact_id, space_id)
        REFERENCES artifacts (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    ADD CONSTRAINT parse_reports_text_artifact_fk FOREIGN KEY (extracted_text_artifact_id, space_id)
        REFERENCES artifacts (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE source_documents
    ADD CONSTRAINT source_documents_active_revision_fk FOREIGN KEY (active_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

ALTER TABLE active_document_pointers
    ADD CONSTRAINT active_document_pointers_revision_fk FOREIGN KEY (active_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE OR REPLACE FUNCTION ragforge_reject_immutable_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'immutable version record % cannot be updated', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER source_versions_immutable_trg BEFORE UPDATE ON source_versions FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER pipeline_versions_immutable_trg BEFORE UPDATE ON pipeline_versions FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER artifacts_immutable_trg BEFORE UPDATE ON artifacts FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER document_revisions_immutable_trg BEFORE UPDATE ON document_revisions FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER parse_reports_immutable_trg BEFORE UPDATE ON parse_reports FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
