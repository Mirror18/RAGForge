-- Phase 2 domain persistence baseline: provider/profile/route, prompt versions, and runs.
--
-- Migration policy: append-only from V2. No V1/V2 object is altered. The tables below
-- intentionally retain only hashes, references, structured metadata, and version IDs;
-- credentials and raw prompt/document content are never persisted here.
--
-- Rollback/compatibility: this migration is forward-only in normal deployments. A
-- rollback restores a PostgreSQL backup taken before V3 and deploys the previous
-- application. A V2 application can run against a pre-V3 backup, but must not be
-- pointed at a V3 database because it cannot observe or manage these tables.

CREATE TABLE provider_connections (
    id UUID PRIMARY KEY,
    space_id UUID REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    provider_key VARCHAR(120) NOT NULL,
    display_name VARCHAR(160) NOT NULL,
    provider_type VARCHAR(40) NOT NULL,
    endpoint_uri VARCHAR(2048) NOT NULL,
    credential_ref VARCHAR(512),
    credential_hash CHAR(64),
    auth_scheme VARCHAR(40) NOT NULL DEFAULT 'NONE',
    non_secret_headers JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    egress_policy VARCHAR(32) NOT NULL DEFAULT 'LOCAL_ONLY',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT provider_connections_type_ck CHECK (provider_type IN ('OLLAMA', 'OPENAI_COMPATIBLE', 'AI_RUNTIME')),
    CONSTRAINT provider_connections_status_ck CHECK (status IN ('DRAFT', 'ACTIVE', 'DISABLED', 'UNHEALTHY')),
    CONSTRAINT provider_connections_egress_ck CHECK (egress_policy IN ('LOCAL_ONLY', 'CLOUD_ALLOWED')),
    CONSTRAINT provider_connections_credential_hash_ck CHECK (
        credential_hash IS NULL OR credential_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT provider_connections_secret_metadata_ck CHECK (
        credential_ref IS NOT NULL OR credential_hash IS NULL
    ),
    CONSTRAINT provider_connections_identity_uq UNIQUE (id, space_id)
);

CREATE UNIQUE INDEX provider_connections_global_key_uq
    ON provider_connections (provider_key) WHERE space_id IS NULL;
CREATE UNIQUE INDEX provider_connections_space_key_uq
    ON provider_connections (space_id, provider_key) WHERE space_id IS NOT NULL;
CREATE INDEX provider_connections_space_created_idx
    ON provider_connections (space_id, created_at DESC);

CREATE TABLE model_profile_versions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    provider_connection_id UUID NOT NULL,
    profile_key VARCHAR(120) NOT NULL,
    version_no INTEGER NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    declared_capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    verified_capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    context_window INTEGER,
    max_output_tokens INTEGER,
    embedding_dimension INTEGER,
    tokenizer VARCHAR(120),
    rate_limit JSONB NOT NULL DEFAULT '{}'::jsonb,
    price_table_ref VARCHAR(255),
    allowed_parameters JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT model_profile_versions_version_ck CHECK (version_no > 0),
    CONSTRAINT model_profile_versions_status_ck CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT model_profile_versions_positive_limits_ck CHECK (
        (context_window IS NULL OR context_window > 0)
        AND (max_output_tokens IS NULL OR max_output_tokens > 0)
        AND (embedding_dimension IS NULL OR embedding_dimension > 0)
    ),
    CONSTRAINT model_profile_versions_provider_fk FOREIGN KEY (provider_connection_id, space_id)
        REFERENCES provider_connections (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT model_profile_versions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT model_profile_versions_key_uq UNIQUE (space_id, profile_key, version_no)
);

CREATE INDEX model_profile_versions_space_created_idx
    ON model_profile_versions (space_id, created_at DESC);
CREATE INDEX model_profile_versions_space_key_idx
    ON model_profile_versions (space_id, profile_key, version_no DESC);

CREATE TABLE model_route_versions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    route_key VARCHAR(120) NOT NULL,
    version_no INTEGER NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    egress_policy VARCHAR(32) NOT NULL DEFAULT 'LOCAL_ONLY',
    allow_cloud_egress BOOLEAN NOT NULL DEFAULT FALSE,
    selection_policy VARCHAR(32) NOT NULL DEFAULT 'ORDERED_FAILOVER',
    compatibility JSONB NOT NULL DEFAULT '{}'::jsonb,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT model_route_versions_version_ck CHECK (version_no > 0),
    CONSTRAINT model_route_versions_purpose_ck CHECK (purpose IN ('CHAT', 'EMBEDDING', 'RERANK')),
    CONSTRAINT model_route_versions_egress_ck CHECK (egress_policy IN ('LOCAL_ONLY', 'CLOUD_ALLOWED')),
    CONSTRAINT model_route_versions_cloud_ck CHECK ((egress_policy = 'CLOUD_ALLOWED') = allow_cloud_egress),
    CONSTRAINT model_route_versions_selection_ck CHECK (selection_policy IN ('ORDERED_FAILOVER', 'SINGLE')),
    CONSTRAINT model_route_versions_status_ck CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT model_route_versions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT model_route_versions_key_uq UNIQUE (space_id, route_key, version_no)
);

CREATE INDEX model_route_versions_space_created_idx
    ON model_route_versions (space_id, created_at DESC);
CREATE INDEX model_route_versions_space_purpose_idx
    ON model_route_versions (space_id, purpose, version_no DESC);

CREATE TABLE model_route_candidates (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    model_route_version_id UUID NOT NULL,
    candidate_no INTEGER NOT NULL,
    model_profile_version_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT model_route_candidates_order_ck CHECK (candidate_no > 0),
    CONSTRAINT model_route_candidates_route_fk FOREIGN KEY (model_route_version_id, space_id)
        REFERENCES model_route_versions (id, space_id) ON DELETE CASCADE,
    CONSTRAINT model_route_candidates_profile_fk FOREIGN KEY (model_profile_version_id, space_id)
        REFERENCES model_profile_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT model_route_candidates_order_uq UNIQUE (model_route_version_id, candidate_no)
);

CREATE INDEX model_route_candidates_space_idx
    ON model_route_candidates (space_id, model_route_version_id, candidate_no);

CREATE TABLE space_model_bindings (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    binding_key VARCHAR(120) NOT NULL,
    version_no INTEGER NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    model_route_version_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT space_model_bindings_version_ck CHECK (version_no > 0),
    CONSTRAINT space_model_bindings_purpose_ck CHECK (purpose IN ('CHAT', 'EMBEDDING', 'RERANK')),
    CONSTRAINT space_model_bindings_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT space_model_bindings_route_fk FOREIGN KEY (model_route_version_id, space_id)
        REFERENCES model_route_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT space_model_bindings_key_uq UNIQUE (space_id, binding_key, version_no)
);

CREATE INDEX space_model_bindings_space_purpose_idx
    ON space_model_bindings (space_id, purpose, version_no DESC);

CREATE TABLE prompt_versions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    prompt_key VARCHAR(120) NOT NULL,
    version_no INTEGER NOT NULL,
    template TEXT NOT NULL,
    template_hash CHAR(64) NOT NULL,
    variables_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    change_note VARCHAR(2000),
    created_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT prompt_versions_version_ck CHECK (version_no > 0),
    CONSTRAINT prompt_versions_hash_ck CHECK (template_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT prompt_versions_status_ck CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT prompt_versions_key_uq UNIQUE (space_id, prompt_key, version_no),
    CONSTRAINT prompt_versions_identity_uq UNIQUE (id, space_id)
);

CREATE INDEX prompt_versions_space_created_idx
    ON prompt_versions (space_id, created_at DESC);
CREATE INDEX prompt_versions_space_key_idx
    ON prompt_versions (space_id, prompt_key, version_no DESC);

CREATE TABLE space_prompt_bindings (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    binding_key VARCHAR(120) NOT NULL,
    version_no INTEGER NOT NULL,
    prompt_version_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT space_prompt_bindings_version_ck CHECK (version_no > 0),
    CONSTRAINT space_prompt_bindings_status_ck CHECK (status IN ('ACTIVE', 'RETIRED')),
    CONSTRAINT space_prompt_bindings_prompt_fk FOREIGN KEY (prompt_version_id, space_id)
        REFERENCES prompt_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT space_prompt_bindings_key_uq UNIQUE (space_id, binding_key, version_no)
);

CREATE INDEX space_prompt_bindings_space_key_idx
    ON space_prompt_bindings (space_id, binding_key, version_no DESC);

CREATE TABLE runs (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    correlation_id UUID NOT NULL,
    request_kind VARCHAR(32) NOT NULL DEFAULT 'CHAT',
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    model_route_version_id UUID,
    prompt_version_id UUID,
    input_hash CHAR(64),
    output_hash CHAR(64),
    error_class VARCHAR(40),
    error_code VARCHAR(120),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT runs_request_kind_ck CHECK (request_kind IN ('CHAT', 'EMBEDDING', 'RERANK')),
    CONSTRAINT runs_status_ck CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT runs_error_ck CHECK (error_class IS NULL OR error_class IN (
        'AUTHENTICATION', 'RATE_LIMIT', 'QUOTA', 'MODEL_NOT_FOUND', 'CONTEXT_OVERFLOW',
        'CONTENT_POLICY', 'TIMEOUT', 'UNAVAILABLE', 'UNSUPPORTED_CAPABILITY', 'INVALID_RESPONSE',
        'CANCELLED', 'SPACE_EGRESS_DENIED', 'IDEMPOTENCY_CONFLICT'
    )),
    CONSTRAINT runs_input_hash_ck CHECK (input_hash IS NULL OR input_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT runs_output_hash_ck CHECK (output_hash IS NULL OR output_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT runs_route_fk FOREIGN KEY (model_route_version_id, space_id)
        REFERENCES model_route_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT runs_prompt_fk FOREIGN KEY (prompt_version_id, space_id)
        REFERENCES prompt_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT runs_identity_uq UNIQUE (id, space_id)
);

CREATE INDEX runs_space_created_idx ON runs (space_id, created_at DESC);
CREATE INDEX runs_space_status_idx ON runs (space_id, status, created_at DESC);
CREATE INDEX runs_correlation_idx ON runs (space_id, correlation_id);

CREATE TABLE run_steps (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    step_key VARCHAR(120) NOT NULL,
    step_type VARCHAR(32) NOT NULL,
    attempt INTEGER NOT NULL DEFAULT 1,
    sequence_no INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    error_class VARCHAR(40),
    error_code VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT run_steps_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT run_steps_attempt_ck CHECK (attempt > 0),
    CONSTRAINT run_steps_sequence_ck CHECK (sequence_no >= 0),
    CONSTRAINT run_steps_type_ck CHECK (step_type IN ('REWRITE', 'RETRIEVE', 'RERANK', 'TOOL', 'GENERATE')),
    CONSTRAINT run_steps_status_ck CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT run_steps_error_ck CHECK (error_class IS NULL OR error_class IN (
        'AUTHENTICATION', 'RATE_LIMIT', 'QUOTA', 'MODEL_NOT_FOUND', 'CONTEXT_OVERFLOW',
        'CONTENT_POLICY', 'TIMEOUT', 'UNAVAILABLE', 'UNSUPPORTED_CAPABILITY', 'INVALID_RESPONSE',
        'CANCELLED', 'SPACE_EGRESS_DENIED', 'IDEMPOTENCY_CONFLICT'
    )),
    CONSTRAINT run_steps_identity_uq UNIQUE (run_id, step_key, attempt),
    CONSTRAINT run_steps_id_space_uq UNIQUE (id, space_id)
);

CREATE INDEX run_steps_space_run_idx ON run_steps (space_id, run_id, sequence_no, created_at);

CREATE TABLE model_invocations (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    step_id UUID NOT NULL,
    provider_connection_id UUID NOT NULL,
    model_profile_version_id UUID NOT NULL,
    model_route_version_id UUID,
    prompt_version_id UUID,
    provider_request_identity VARCHAR(255) NOT NULL,
    prompt_render_hash CHAR(64),
    request_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    response_hash CHAR(64),
    status VARCHAR(32) NOT NULL,
    error_class VARCHAR(40),
    error_code VARCHAR(120),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT model_invocations_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT model_invocations_step_fk FOREIGN KEY (step_id, space_id)
        REFERENCES run_steps (id, space_id) ON DELETE CASCADE,
    CONSTRAINT model_invocations_provider_fk FOREIGN KEY (provider_connection_id, space_id)
        REFERENCES provider_connections (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT model_invocations_profile_fk FOREIGN KEY (model_profile_version_id, space_id)
        REFERENCES model_profile_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT model_invocations_route_fk FOREIGN KEY (model_route_version_id, space_id)
        REFERENCES model_route_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT model_invocations_prompt_fk FOREIGN KEY (prompt_version_id, space_id)
        REFERENCES prompt_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT model_invocations_status_ck CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    CONSTRAINT model_invocations_error_ck CHECK (error_class IS NULL OR error_class IN (
        'AUTHENTICATION', 'RATE_LIMIT', 'QUOTA', 'MODEL_NOT_FOUND', 'CONTEXT_OVERFLOW',
        'CONTENT_POLICY', 'TIMEOUT', 'UNAVAILABLE', 'UNSUPPORTED_CAPABILITY', 'INVALID_RESPONSE',
        'CANCELLED', 'SPACE_EGRESS_DENIED', 'IDEMPOTENCY_CONFLICT'
    )),
    CONSTRAINT model_invocations_prompt_hash_ck CHECK (
        prompt_render_hash IS NULL OR prompt_render_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT model_invocations_response_hash_ck CHECK (
        response_hash IS NULL OR response_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT model_invocations_identity_uq UNIQUE (space_id, provider_request_identity),
    CONSTRAINT model_invocations_id_space_uq UNIQUE (id, space_id),
    CONSTRAINT model_invocations_space_identity_uq UNIQUE (id, space_id, provider_request_identity)
);

CREATE INDEX model_invocations_space_created_idx
    ON model_invocations (space_id, created_at DESC);
CREATE INDEX model_invocations_space_run_idx
    ON model_invocations (space_id, run_id, created_at);

CREATE TABLE usage_ledger (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    model_invocation_id UUID NOT NULL,
    provider_request_identity VARCHAR(255) NOT NULL,
    usage_source VARCHAR(32) NOT NULL,
    dedupe_key VARCHAR(512) NOT NULL,
    input_tokens BIGINT,
    output_tokens BIGINT,
    total_tokens BIGINT,
    estimated_cost NUMERIC(20, 8),
    currency CHAR(3),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT usage_ledger_invocation_fk FOREIGN KEY (model_invocation_id, space_id)
        REFERENCES model_invocations (id, space_id) ON DELETE CASCADE,
    CONSTRAINT usage_ledger_invocation_identity_fk FOREIGN KEY
        (model_invocation_id, space_id, provider_request_identity)
        REFERENCES model_invocations (id, space_id, provider_request_identity) ON DELETE CASCADE,
    CONSTRAINT usage_ledger_source_ck CHECK (usage_source IN ('PROVIDER_REPORTED', 'LOCAL_ESTIMATE')),
    CONSTRAINT usage_ledger_dedupe_key_ck CHECK (
        char_length(dedupe_key) BETWEEN 10 AND 512
        AND dedupe_key ~ '^[A-Za-z0-9._:-]+$'
    ),
    CONSTRAINT usage_ledger_tokens_ck CHECK (
        (input_tokens IS NULL OR input_tokens >= 0)
        AND (output_tokens IS NULL OR output_tokens >= 0)
        AND (total_tokens IS NULL OR total_tokens >= 0)
    ),
    CONSTRAINT usage_ledger_cost_ck CHECK (estimated_cost IS NULL OR estimated_cost >= 0),
    CONSTRAINT usage_ledger_identity_uq UNIQUE (space_id, model_invocation_id, usage_source, dedupe_key)
);

CREATE INDEX usage_ledger_space_created_idx ON usage_ledger (space_id, created_at DESC);
CREATE INDEX usage_ledger_space_request_idx
    ON usage_ledger (space_id, provider_request_identity);

CREATE OR REPLACE FUNCTION ragforge_reject_immutable_update()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'immutable version record % cannot be updated', TG_TABLE_NAME
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER model_profile_versions_immutable_trg
    BEFORE UPDATE ON model_profile_versions
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER model_route_versions_immutable_trg
    BEFORE UPDATE ON model_route_versions
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER model_route_candidates_immutable_trg
    BEFORE UPDATE ON model_route_candidates
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER space_model_bindings_immutable_trg
    BEFORE UPDATE ON space_model_bindings
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER prompt_versions_immutable_trg
    BEFORE UPDATE ON prompt_versions
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER space_prompt_bindings_immutable_trg
    BEFORE UPDATE ON space_prompt_bindings
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER model_invocations_immutable_trg
    BEFORE UPDATE ON model_invocations
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
