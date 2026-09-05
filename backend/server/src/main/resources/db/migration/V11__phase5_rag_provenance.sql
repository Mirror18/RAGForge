-- Phase 5 RAG provenance projection boundary.
--
-- V11 is append-only.  The existing Phase 2 tables remain the source of
-- truth for no-RAG runs and keep their existing nullable prompt semantics.
-- These tables are an immutable, space-scoped projection for a future Answer
-- Agent.  They persist version IDs, hashes and opaque references only; raw
-- prompts, documents, evidence text, tool schemas and model output are not
-- accepted by this boundary.
--
-- Compatibility window: deploy V11 before an Answer Agent writer, then deploy
-- readers that tolerate an absent projection for historical no-RAG runs.  A
-- V10 reader can continue to use the old tables because V11 adds no required
-- column to them.  Rollback is forward-only: stop new V11 writes and cut the
-- application pointer back to the last compatible prompt/index/profile/route;
-- do not drop V11 rows or run a destructive down migration.

-- These redundant identity keys make the existing parentage explicit for the
-- composite FKs below without changing any no-RAG write or read behavior.
ALTER TABLE run_steps
    ADD CONSTRAINT run_steps_identity_lineage_uq UNIQUE (id, space_id, run_id);
ALTER TABLE model_invocations
    ADD CONSTRAINT model_invocations_identity_lineage_uq
        UNIQUE (id, space_id, run_id, step_id);

CREATE TABLE rag_prompt_versions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    prompt_key VARCHAR(120) NOT NULL,
    version_no INTEGER NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    prompt_opaque_ref VARCHAR(512) NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    variables_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    output_contract JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_by_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    created_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT rag_prompt_versions_version_ck CHECK (version_no > 0),
    CONSTRAINT rag_prompt_versions_purpose_ck CHECK (purpose IN ('RAG_ANSWER', 'RAG_REWRITE', 'RAG_QUERY')),
    CONSTRAINT rag_prompt_versions_ref_ck CHECK (
        length(prompt_opaque_ref) BETWEEN 1 AND 512
        AND prompt_opaque_ref !~ '[[:space:]]'
        AND prompt_opaque_ref !~ '[[:cntrl:]]'
        AND prompt_opaque_ref !~* '(raw_prompt|raw_document|raw_output|fulltext|responsebody|promptbody)'
    ),
    CONSTRAINT rag_prompt_versions_hash_ck CHECK (prompt_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT rag_prompt_versions_schema_ck CHECK (
        jsonb_typeof(variables_schema) = 'object' AND jsonb_typeof(output_contract) = 'object'
    ),
    CONSTRAINT rag_prompt_versions_author_fk FOREIGN KEY (space_id, created_by_user_id)
        REFERENCES space_memberships (space_id, user_id) ON DELETE RESTRICT,
    CONSTRAINT rag_prompt_versions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_prompt_versions_key_uq UNIQUE (space_id, prompt_key, version_no)
);

CREATE INDEX rag_prompt_versions_space_key_idx
    ON rag_prompt_versions (space_id, prompt_key, version_no DESC);

CREATE TABLE rag_run_provenance (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    rag_prompt_version_id UUID NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    index_version_id UUID NOT NULL,
    retrieval_profile_id UUID NOT NULL,
    retrieval_profile_version INTEGER NOT NULL,
    model_route_version_id UUID NOT NULL,
    model_profile_version_id UUID NOT NULL,
    evidence_bundle_version INTEGER NOT NULL,
    evidence_bundle_hash CHAR(64) NOT NULL,
    evidence_bundle_ref VARCHAR(512) NOT NULL,
    tool_schema_versions JSONB NOT NULL DEFAULT '{}'::jsonb,
    dataset_hash CHAR(64) NOT NULL,
    config_hash CHAR(64) NOT NULL,
    trace_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT rag_run_provenance_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_run_provenance_prompt_fk FOREIGN KEY (rag_prompt_version_id, space_id)
        REFERENCES rag_prompt_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_run_provenance_index_fk FOREIGN KEY (index_version_id, space_id)
        REFERENCES index_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_run_provenance_profile_fk FOREIGN KEY
        (space_id, retrieval_profile_id, retrieval_profile_version)
        REFERENCES retrieval_profiles (space_id, profile_id, version_no) ON DELETE RESTRICT,
    CONSTRAINT rag_run_provenance_route_fk FOREIGN KEY (model_route_version_id, space_id)
        REFERENCES model_route_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_run_provenance_model_fk FOREIGN KEY (model_profile_version_id, space_id)
        REFERENCES model_profile_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_run_provenance_hash_ck CHECK (
        prompt_hash ~ '^[0-9a-f]{64}$'
        AND evidence_bundle_hash ~ '^[0-9a-f]{64}$'
        AND dataset_hash ~ '^[0-9a-f]{64}$'
        AND config_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT rag_run_provenance_version_ck CHECK (evidence_bundle_version > 0),
    CONSTRAINT rag_run_provenance_ref_ck CHECK (
        length(evidence_bundle_ref) BETWEEN 1 AND 512
        AND evidence_bundle_ref !~ '[[:space:]]'
        AND evidence_bundle_ref !~ '[[:cntrl:]]'
        AND evidence_bundle_ref !~* '(raw_document|raw_output|fulltext|responsebody|promptbody)'
    ),
    CONSTRAINT rag_run_provenance_tool_schema_ck CHECK (jsonb_typeof(tool_schema_versions) = 'object'),
    CONSTRAINT rag_run_provenance_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_run_provenance_run_uq UNIQUE (space_id, run_id)
);

CREATE INDEX rag_run_provenance_space_created_idx
    ON rag_run_provenance (space_id, created_at DESC);

CREATE TABLE rag_step_provenance (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    step_id UUID NOT NULL,
    rag_prompt_version_id UUID NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    index_version_id UUID NOT NULL,
    retrieval_profile_id UUID NOT NULL,
    retrieval_profile_version INTEGER NOT NULL,
    model_route_version_id UUID NOT NULL,
    model_profile_version_id UUID NOT NULL,
    evidence_bundle_version INTEGER NOT NULL,
    evidence_bundle_hash CHAR(64) NOT NULL,
    evidence_bundle_ref VARCHAR(512) NOT NULL,
    tool_schema_versions JSONB NOT NULL DEFAULT '{}'::jsonb,
    dataset_hash CHAR(64) NOT NULL,
    config_hash CHAR(64) NOT NULL,
    trace_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT rag_step_provenance_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_step_provenance_step_fk FOREIGN KEY (step_id, space_id)
        REFERENCES run_steps (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_step_provenance_lineage_fk FOREIGN KEY (step_id, space_id, run_id)
        REFERENCES run_steps (id, space_id, run_id) ON DELETE CASCADE,
    CONSTRAINT rag_step_provenance_prompt_fk FOREIGN KEY (rag_prompt_version_id, space_id)
        REFERENCES rag_prompt_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_step_provenance_index_fk FOREIGN KEY (index_version_id, space_id)
        REFERENCES index_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_step_provenance_profile_fk FOREIGN KEY
        (space_id, retrieval_profile_id, retrieval_profile_version)
        REFERENCES retrieval_profiles (space_id, profile_id, version_no) ON DELETE RESTRICT,
    CONSTRAINT rag_step_provenance_route_fk FOREIGN KEY (model_route_version_id, space_id)
        REFERENCES model_route_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_step_provenance_model_fk FOREIGN KEY (model_profile_version_id, space_id)
        REFERENCES model_profile_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_step_provenance_hash_ck CHECK (
        prompt_hash ~ '^[0-9a-f]{64}$'
        AND evidence_bundle_hash ~ '^[0-9a-f]{64}$'
        AND dataset_hash ~ '^[0-9a-f]{64}$'
        AND config_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT rag_step_provenance_version_ck CHECK (evidence_bundle_version > 0),
    CONSTRAINT rag_step_provenance_ref_ck CHECK (
        length(evidence_bundle_ref) BETWEEN 1 AND 512
        AND evidence_bundle_ref !~ '[[:space:]]'
        AND evidence_bundle_ref !~ '[[:cntrl:]]'
        AND evidence_bundle_ref !~* '(raw_document|raw_output|fulltext|responsebody|promptbody)'
    ),
    CONSTRAINT rag_step_provenance_tool_schema_ck CHECK (jsonb_typeof(tool_schema_versions) = 'object'),
    CONSTRAINT rag_step_provenance_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_step_provenance_step_uq UNIQUE (space_id, step_id)
);

CREATE INDEX rag_step_provenance_space_run_idx
    ON rag_step_provenance (space_id, run_id, created_at, id);

CREATE TABLE rag_model_invocation_provenance (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    run_id UUID NOT NULL,
    step_id UUID NOT NULL,
    model_invocation_id UUID NOT NULL,
    rag_prompt_version_id UUID NOT NULL,
    prompt_hash CHAR(64) NOT NULL,
    index_version_id UUID NOT NULL,
    retrieval_profile_id UUID NOT NULL,
    retrieval_profile_version INTEGER NOT NULL,
    model_route_version_id UUID NOT NULL,
    model_profile_version_id UUID NOT NULL,
    evidence_bundle_version INTEGER NOT NULL,
    evidence_bundle_hash CHAR(64) NOT NULL,
    evidence_bundle_ref VARCHAR(512) NOT NULL,
    tool_schema_versions JSONB NOT NULL DEFAULT '{}'::jsonb,
    dataset_hash CHAR(64) NOT NULL,
    config_hash CHAR(64) NOT NULL,
    trace_id UUID NOT NULL,
    correlation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT rag_invocation_provenance_run_fk FOREIGN KEY (run_id, space_id)
        REFERENCES runs (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_invocation_provenance_step_fk FOREIGN KEY (step_id, space_id)
        REFERENCES run_steps (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_invocation_provenance_invocation_fk FOREIGN KEY (model_invocation_id, space_id)
        REFERENCES model_invocations (id, space_id) ON DELETE CASCADE,
    CONSTRAINT rag_invocation_provenance_lineage_fk FOREIGN KEY
        (model_invocation_id, space_id, run_id, step_id)
        REFERENCES model_invocations (id, space_id, run_id, step_id) ON DELETE CASCADE,
    CONSTRAINT rag_invocation_provenance_prompt_fk FOREIGN KEY (rag_prompt_version_id, space_id)
        REFERENCES rag_prompt_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_invocation_provenance_index_fk FOREIGN KEY (index_version_id, space_id)
        REFERENCES index_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_invocation_provenance_profile_fk FOREIGN KEY
        (space_id, retrieval_profile_id, retrieval_profile_version)
        REFERENCES retrieval_profiles (space_id, profile_id, version_no) ON DELETE RESTRICT,
    CONSTRAINT rag_invocation_provenance_route_fk FOREIGN KEY (model_route_version_id, space_id)
        REFERENCES model_route_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_invocation_provenance_model_fk FOREIGN KEY (model_profile_version_id, space_id)
        REFERENCES model_profile_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT rag_invocation_provenance_hash_ck CHECK (
        prompt_hash ~ '^[0-9a-f]{64}$'
        AND evidence_bundle_hash ~ '^[0-9a-f]{64}$'
        AND dataset_hash ~ '^[0-9a-f]{64}$'
        AND config_hash ~ '^[0-9a-f]{64}$'
    ),
    CONSTRAINT rag_invocation_provenance_version_ck CHECK (evidence_bundle_version > 0),
    CONSTRAINT rag_invocation_provenance_ref_ck CHECK (
        length(evidence_bundle_ref) BETWEEN 1 AND 512
        AND evidence_bundle_ref !~ '[[:space:]]'
        AND evidence_bundle_ref !~ '[[:cntrl:]]'
        AND evidence_bundle_ref !~* '(raw_document|raw_output|fulltext|responsebody|promptbody)'
    ),
    CONSTRAINT rag_invocation_provenance_tool_schema_ck CHECK (jsonb_typeof(tool_schema_versions) = 'object'),
    CONSTRAINT rag_invocation_provenance_identity_uq UNIQUE (id, space_id),
    CONSTRAINT rag_invocation_provenance_invocation_uq UNIQUE (space_id, model_invocation_id)
);

CREATE INDEX rag_invocation_provenance_space_run_idx
    ON rag_model_invocation_provenance (space_id, run_id, created_at, id);

CREATE TRIGGER rag_prompt_versions_immutable_trg
    BEFORE UPDATE ON rag_prompt_versions
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER rag_run_provenance_immutable_trg
    BEFORE UPDATE ON rag_run_provenance
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER rag_step_provenance_immutable_trg
    BEFORE UPDATE ON rag_step_provenance
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
CREATE TRIGGER rag_invocation_provenance_immutable_trg
    BEFORE UPDATE ON rag_model_invocation_provenance
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
