-- Durable, space-scoped run event stream used for SSE replay after a process restart.
-- Payloads cross the existing PayloadPolicy boundary before insertion; this table
-- never stores provider credentials, raw prompts, provider bodies, or evidence text.

CREATE TABLE rag_run_event_streams (
    run_id UUID NOT NULL,
    space_id UUID NOT NULL,
    latest_sequence BIGINT NOT NULL DEFAULT 0,
    cancelled_at TIMESTAMPTZ,
    cancellation_event_id UUID,
    CONSTRAINT rag_run_event_streams_pk PRIMARY KEY (run_id, space_id),
    CONSTRAINT rag_run_event_streams_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_run_event_streams_sequence_ck CHECK (latest_sequence >= 0)
);

CREATE TABLE rag_run_events (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    space_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    event_type VARCHAR(120) NOT NULL,
    event_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    CONSTRAINT rag_run_events_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_run_events_sequence_uq UNIQUE (run_id, space_id, sequence_no),
    CONSTRAINT rag_run_events_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_run_events_sequence_ck CHECK (sequence_no > 0),
    CONSTRAINT rag_run_events_version_ck CHECK (event_version > 0),
    CONSTRAINT rag_run_events_type_ck CHECK (length(event_type) BETWEEN 1 AND 120)
);

CREATE INDEX rag_run_events_replay_idx
    ON rag_run_events (space_id, run_id, sequence_no, expires_at);
CREATE INDEX rag_run_events_expiry_idx
    ON rag_run_events (expires_at);

-- The stream cursor is intentionally mutable by the store. Event rows themselves
-- are append-only.
CREATE TRIGGER rag_run_events_immutable_trg
    BEFORE UPDATE ON rag_run_events FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
