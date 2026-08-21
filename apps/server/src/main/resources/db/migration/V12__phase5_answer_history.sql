-- Phase 5 answer history and citation provenance projection.
--
-- V12 is append-only with respect to answer content and provenance. The only
-- permitted lifecycle operation is deleting a complete aggregate after its
-- retention deadline; ON DELETE CASCADE removes claims, citations, abstentions
-- and replay events together. No raw prompt, provider request/response body,
-- evidence text, URL, filename, or tool body is accepted by these tables.

CREATE TABLE rag_answers (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    schema_version VARCHAR(16) NOT NULL DEFAULT 'v1',
    status VARCHAR(16) NOT NULL,
    answer_text TEXT,
    answer_hash CHAR(64) NOT NULL,
    citation_hash CHAR(64) NOT NULL,
    evidence_bundle_id UUID,
    evidence_bundle_version INTEGER NOT NULL DEFAULT 0,
    evidence_bundle_hash CHAR(64),
    evidence_bundle_ref VARCHAR(512),
    index_version_id UUID,
    retrieval_profile_id UUID,
    retrieval_profile_version INTEGER NOT NULL DEFAULT 0,
    rag_prompt_version_id UUID,
    prompt_hash CHAR(64),
    model_route_version_id UUID,
    model_profile_version_id UUID,
    model_version VARCHAR(255),
    tool_schema_versions JSONB NOT NULL DEFAULT '{}'::jsonb,
    tool_call_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    dataset_hash CHAR(64) NOT NULL,
    config_hash CHAR(64) NOT NULL,
    trace_id UUID NOT NULL,
    retention_deadline TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT rag_answers_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_answers_lineage_uq UNIQUE (id, space_id, run_id),
    CONSTRAINT rag_answers_idempotency_uq UNIQUE (space_id, idempotency_key),
    CONSTRAINT rag_answers_run_uq UNIQUE (space_id, run_id),
    CONSTRAINT rag_answers_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_answers_schema_ck CHECK (schema_version = 'v1'),
    CONSTRAINT rag_answers_status_ck CHECK (status IN ('COMPLETED', 'ABSTAINED', 'FAILED', 'CANCELLED')),
    CONSTRAINT rag_answers_key_ck CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{16,255}$'),
    CONSTRAINT rag_answers_hash_ck CHECK (answer_hash ~ '^[0-9a-f]{64}$' AND citation_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rag_answers_dataset_config_ck CHECK (dataset_hash ~ '^[0-9a-f]{64}$' AND config_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rag_answers_status_text_ck CHECK (
        (status = 'COMPLETED' AND answer_text IS NOT NULL AND length(answer_text) BETWEEN 1 AND 200000)
        OR (status <> 'COMPLETED' AND answer_text IS NULL)
    ),
    CONSTRAINT rag_answers_evidence_ck CHECK (
        (evidence_bundle_id IS NULL AND evidence_bundle_version = 0
            AND evidence_bundle_hash IS NULL AND evidence_bundle_ref IS NULL)
        OR (evidence_bundle_id IS NOT NULL AND evidence_bundle_version > 0
            AND evidence_bundle_hash ~ '^[0-9a-f]{64}$'
            AND evidence_bundle_ref IS NOT NULL
            AND length(evidence_bundle_ref) BETWEEN 1 AND 512
            AND evidence_bundle_ref !~ '[[:space:]]'
            AND evidence_bundle_ref !~ '[[:cntrl:]]'
            AND evidence_bundle_ref !~* '(raw_document|raw_output|fulltext|responsebody|promptbody|https?://)')
    ),
    CONSTRAINT rag_answers_prompt_ck CHECK (prompt_hash IS NULL OR prompt_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rag_answers_profile_ck CHECK (
        (retrieval_profile_id IS NULL AND retrieval_profile_version = 0)
        OR (retrieval_profile_id IS NOT NULL AND retrieval_profile_version > 0)
    ),
    CONSTRAINT rag_answers_tool_schema_ck CHECK (jsonb_typeof(tool_schema_versions) = 'object'),
    CONSTRAINT rag_answers_tool_calls_ck CHECK (jsonb_typeof(tool_call_ids) = 'array'
        AND jsonb_array_length(tool_call_ids) BETWEEN 0 AND 100),
    CONSTRAINT rag_answers_retention_ck CHECK (retention_deadline >= created_at)
);

CREATE INDEX rag_answers_space_created_idx ON rag_answers (space_id, created_at DESC);
CREATE INDEX rag_answers_space_run_idx ON rag_answers (space_id, run_id);
CREATE INDEX rag_answers_retention_idx ON rag_answers (retention_deadline);

CREATE TABLE rag_answer_claims (
    id UUID PRIMARY KEY,
    answer_id UUID NOT NULL,
    space_id UUID NOT NULL,
    run_id UUID NOT NULL,
    claim_text TEXT NOT NULL,
    citation_tokens JSONB NOT NULL,
    answer_char_start INTEGER NOT NULL,
    answer_char_end INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT rag_answer_claims_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_answer_claims_lineage_uq UNIQUE (id, space_id, run_id),
    CONSTRAINT rag_answer_claims_answer_fk FOREIGN KEY (answer_id, space_id, run_id)
        REFERENCES rag_answers (id, space_id, run_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_claims_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_claims_scope_ck CHECK (answer_char_start >= 0 AND answer_char_end >= answer_char_start),
    CONSTRAINT rag_answer_claims_tokens_ck CHECK (jsonb_typeof(citation_tokens) = 'array'
        AND jsonb_array_length(citation_tokens) BETWEEN 1 AND 20)
);

CREATE INDEX rag_answer_claims_space_answer_idx ON rag_answer_claims (space_id, answer_id, id);

CREATE TABLE rag_answer_citations (
    id UUID PRIMARY KEY,
    answer_id UUID NOT NULL,
    claim_id UUID NOT NULL,
    space_id UUID NOT NULL,
    run_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    evidence_bundle_id UUID NOT NULL,
    evidence_bundle_version INTEGER NOT NULL,
    evidence_bundle_hash CHAR(64) NOT NULL,
    index_version_id UUID NOT NULL,
    retrieval_profile_id UUID NOT NULL,
    retrieval_profile_version INTEGER NOT NULL,
    document_revision_id UUID NOT NULL,
    parent_chunk_id UUID NOT NULL,
    child_chunk_id UUID NOT NULL,
    content_ref VARCHAR(512) NOT NULL,
    text_hash CHAR(64) NOT NULL,
    anchor JSONB NOT NULL,
    answer_char_start INTEGER NOT NULL,
    answer_char_end INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT rag_answer_citations_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_answer_citations_evidence_uq UNIQUE (answer_id, evidence_id, claim_id),
    CONSTRAINT rag_answer_citations_answer_fk FOREIGN KEY (answer_id, space_id, run_id)
        REFERENCES rag_answers (id, space_id, run_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_citations_claim_fk FOREIGN KEY (claim_id, space_id, run_id)
        REFERENCES rag_answer_claims (id, space_id, run_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_citations_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_citations_hash_ck CHECK (evidence_bundle_hash ~ '^[0-9a-f]{64}$' AND text_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT rag_answer_citations_version_ck CHECK (evidence_bundle_version > 0 AND retrieval_profile_version > 0),
    CONSTRAINT rag_answer_citations_ref_ck CHECK (
        length(content_ref) BETWEEN 1 AND 512
        AND content_ref !~ '[[:space:]]'
        AND content_ref !~ '[[:cntrl:]]'
        AND content_ref !~* '(https?://|www\\.|\\.pdf|\\.docx|\\.md|raw_document|fulltext|responsebody)'
    ),
    CONSTRAINT rag_answer_citations_anchor_ck CHECK (jsonb_typeof(anchor) = 'object'),
    CONSTRAINT rag_answer_citations_scope_ck CHECK (answer_char_start >= 0 AND answer_char_end >= answer_char_start)
);

CREATE INDEX rag_answer_citations_space_run_evidence_idx
    ON rag_answer_citations (space_id, run_id, evidence_id);

CREATE TABLE rag_answer_abstentions (
    id UUID PRIMARY KEY,
    answer_id UUID NOT NULL,
    space_id UUID NOT NULL,
    run_id UUID NOT NULL,
    reason_code VARCHAR(40) NOT NULL,
    evidence_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
    message VARCHAR(2000) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT rag_answer_abstentions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_answer_abstentions_answer_fk FOREIGN KEY (answer_id, space_id, run_id)
        REFERENCES rag_answers (id, space_id, run_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_abstentions_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_abstentions_evidence_ck CHECK (jsonb_typeof(evidence_ids) = 'array'),
    CONSTRAINT rag_answer_abstentions_message_ck CHECK (length(message) BETWEEN 1 AND 2000)
);

CREATE TABLE rag_answer_events (
    id UUID PRIMARY KEY,
    answer_id UUID NOT NULL,
    space_id UUID NOT NULL,
    run_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT rag_answer_events_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_answer_events_sequence_uq UNIQUE (answer_id, sequence_no),
    CONSTRAINT rag_answer_events_answer_fk FOREIGN KEY (answer_id, space_id, run_id)
        REFERENCES rag_answers (id, space_id, run_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_events_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_answer_events_sequence_ck CHECK (sequence_no >= 0),
    CONSTRAINT rag_answer_events_type_ck CHECK (event_type IN (
        'ANSWER_DELTA', 'ANSWER_CITATION', 'ANSWER_ABSTENTION', 'ANSWER_TOOL',
        'ANSWER_USAGE', 'ANSWER_ERROR', 'ANSWER_DONE')),
    CONSTRAINT rag_answer_events_hash_ck CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rag_answer_events_metadata_ck CHECK (jsonb_typeof(metadata) = 'object')
);

CREATE INDEX rag_answer_events_space_run_idx ON rag_answer_events (space_id, run_id, sequence_no);

CREATE TRIGGER rag_answers_immutable_trg
    BEFORE UPDATE ON rag_answers FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER rag_answer_claims_immutable_trg
    BEFORE UPDATE ON rag_answer_claims FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER rag_answer_citations_immutable_trg
    BEFORE UPDATE ON rag_answer_citations FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER rag_answer_abstentions_immutable_trg
    BEFORE UPDATE ON rag_answer_abstentions FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER rag_answer_events_immutable_trg
    BEFORE UPDATE ON rag_answer_events FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();

-- Purge policy: a scheduled operator calls this function with CURRENT_TIMESTAMP.
-- It removes only complete expired aggregates; immutable history cannot be
-- rewritten before the retention deadline.
CREATE OR REPLACE FUNCTION ragforge_purge_expired_answers(p_now TIMESTAMPTZ)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM rag_answers WHERE retention_deadline < p_now;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;
