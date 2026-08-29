-- Persist only bounded, redacted provider verification metadata. Synthetic probe inputs,
-- provider response bodies, credentials, and authorization headers are never stored.

CREATE TABLE provider_connection_test_runs (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    provider_connection_id UUID NOT NULL,
    model_name VARCHAR(255) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    outcome VARCHAR(16) NOT NULL,
    verified_capabilities JSONB NOT NULL DEFAULT '[]'::jsonb,
    embedding_dimension INTEGER,
    error_class VARCHAR(64),
    retryable BOOLEAN NOT NULL DEFAULT FALSE,
    duration_ms BIGINT NOT NULL,
    tested_by UUID NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    tested_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT provider_connection_test_runs_provider_fk
        FOREIGN KEY (provider_connection_id, space_id)
        REFERENCES provider_connections (id, space_id) ON DELETE CASCADE,
    CONSTRAINT provider_connection_test_runs_purpose_ck CHECK (purpose IN ('CHAT', 'EMBEDDING', 'RERANK')),
    CONSTRAINT provider_connection_test_runs_outcome_ck CHECK (outcome IN ('SUCCEEDED', 'FAILED')),
    CONSTRAINT provider_connection_test_runs_dimension_ck CHECK (
        embedding_dimension IS NULL OR embedding_dimension > 0
    ),
    CONSTRAINT provider_connection_test_runs_duration_ck CHECK (duration_ms >= 0),
    CONSTRAINT provider_connection_test_runs_error_ck CHECK (
        (outcome = 'SUCCEEDED' AND error_class IS NULL)
        OR (outcome = 'FAILED' AND error_class IS NOT NULL)
    )
);

CREATE INDEX provider_connection_test_runs_lookup_idx
    ON provider_connection_test_runs
    (space_id, provider_connection_id, model_name, purpose, tested_at DESC, id DESC);
