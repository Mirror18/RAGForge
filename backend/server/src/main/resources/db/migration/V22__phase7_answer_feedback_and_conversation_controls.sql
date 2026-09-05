-- Phase 7 verifiable-answer commands. Every receipt and feedback row is
-- keyed by space_id so a replay can never address another knowledge space.
CREATE TABLE conversation_command_receipts (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    conversation_id UUID NOT NULL,
    operation VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    expected_version BIGINT,
    result_version BIGINT,
    actor_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT conversation_command_receipts_key_uq UNIQUE (space_id, idempotency_key),
    CONSTRAINT conversation_command_receipts_operation_ck CHECK (operation IN ('CREATE', 'RENAME', 'ARCHIVE', 'DELETE')),
    CONSTRAINT conversation_command_receipts_hash_ck CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT conversation_command_receipts_version_ck CHECK (expected_version IS NULL OR expected_version >= 0)
);

CREATE INDEX conversation_command_receipts_space_time_idx
    ON conversation_command_receipts (space_id, created_at DESC, id DESC);

CREATE TABLE answer_feedback (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    sentiment VARCHAR(16) NOT NULL,
    reason VARCHAR(1000),
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT answer_feedback_key_uq UNIQUE (space_id, idempotency_key),
    CONSTRAINT answer_feedback_actor_target_uq UNIQUE (space_id, run_id, evidence_id, actor_user_id),
    CONSTRAINT answer_feedback_sentiment_ck CHECK (sentiment IN ('HELPFUL', 'NOT_HELPFUL')),
    CONSTRAINT answer_feedback_hash_ck CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT answer_feedback_version_ck CHECK (version >= 0),
    CONSTRAINT answer_feedback_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE
);

CREATE INDEX answer_feedback_space_run_idx
    ON answer_feedback (space_id, run_id, evidence_id);
