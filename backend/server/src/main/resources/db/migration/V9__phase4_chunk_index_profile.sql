-- Phase 4 chunking, index version and retrieval profile persistence.
--
-- Every content-bearing row is space scoped.  Chunks are immutable version
-- records regenerated under a new document revision; overrides, index
-- versions and profile pointers are auditable and versioned.  Vector data
-- lives in Qdrant; PostgreSQL keeps the active pointer and validation facts.

CREATE TABLE parent_chunks (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    document_revision_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    version_no INTEGER NOT NULL,
    heading_path JSONB NOT NULL DEFAULT '[]'::jsonb,
    token_start INTEGER NOT NULL,
    token_end INTEGER NOT NULL,
    char_start INTEGER NOT NULL,
    char_end INTEGER NOT NULL,
    content_ref VARCHAR(1024) NOT NULL,
    immutable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT parent_chunks_version_ck CHECK (version_no > 0),
    CONSTRAINT parent_chunks_index_ck CHECK (chunk_index >= 0),
    CONSTRAINT parent_chunks_range_ck CHECK (
        token_start >= 0 AND token_end >= token_start
        AND char_start >= 0 AND char_end >= char_start),
    CONSTRAINT parent_chunks_immutable_ck CHECK (immutable = TRUE),
    CONSTRAINT parent_chunks_identity_uq UNIQUE (id, space_id),
    CONSTRAINT parent_chunks_key_uq UNIQUE (space_id, document_revision_id, chunk_index),
    CONSTRAINT parent_chunks_revision_fk FOREIGN KEY (document_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT
);

CREATE INDEX parent_chunks_revision_idx ON parent_chunks (space_id, document_revision_id, chunk_index);

CREATE TRIGGER parent_chunks_immutable_trg
    BEFORE UPDATE ON parent_chunks
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();

CREATE TABLE child_chunks (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    parent_chunk_id UUID NOT NULL,
    document_revision_id UUID NOT NULL,
    chunk_index INTEGER NOT NULL,
    version_no INTEGER NOT NULL,
    heading_path JSONB NOT NULL DEFAULT '[]'::jsonb,
    token_start INTEGER NOT NULL,
    token_end INTEGER NOT NULL,
    char_start INTEGER NOT NULL,
    char_end INTEGER NOT NULL,
    page_number INTEGER,
    sheet VARCHAR(255),
    slide_number INTEGER,
    line_start INTEGER,
    line_end INTEGER,
    table_cell VARCHAR(512),
    content_ref VARCHAR(1024) NOT NULL,
    text_hash CHAR(64) NOT NULL,
    immutable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT child_chunks_version_ck CHECK (version_no > 0),
    CONSTRAINT child_chunks_index_ck CHECK (chunk_index >= 0),
    CONSTRAINT child_chunks_range_ck CHECK (
        token_start >= 0 AND token_end >= token_start
        AND char_start >= 0 AND char_end >= char_start),
    CONSTRAINT child_chunks_hash_ck CHECK (text_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT child_chunks_page_ck CHECK (page_number IS NULL OR page_number > 0),
    CONSTRAINT child_chunks_slide_ck CHECK (slide_number IS NULL OR slide_number > 0),
    CONSTRAINT child_chunks_line_ck CHECK (line_start IS NULL OR (line_end IS NOT NULL AND line_end >= line_start)),
    CONSTRAINT child_chunks_immutable_ck CHECK (immutable = TRUE),
    CONSTRAINT child_chunks_identity_uq UNIQUE (id, space_id),
    CONSTRAINT child_chunks_key_uq UNIQUE (space_id, document_revision_id, chunk_index),
    CONSTRAINT child_chunks_parent_fk FOREIGN KEY (parent_chunk_id, space_id)
        REFERENCES parent_chunks (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT child_chunks_revision_fk FOREIGN KEY (document_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT
);

CREATE INDEX child_chunks_revision_idx ON child_chunks (space_id, document_revision_id, chunk_index);
CREATE INDEX child_chunks_parent_idx ON child_chunks (space_id, parent_chunk_id);

CREATE TRIGGER child_chunks_immutable_trg
    BEFORE UPDATE ON child_chunks
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();

CREATE TABLE chunk_overrides (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    child_chunk_id UUID NOT NULL,
    document_revision_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    override_state VARCHAR(32) NOT NULL DEFAULT 'NONE',
    override_source VARCHAR(32) NOT NULL DEFAULT 'MANUAL',
    reason VARCHAR(512) NOT NULL,
    replaced_text_hash CHAR(64),
    created_by UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT chunk_overrides_version_ck CHECK (version_no > 0),
    CONSTRAINT chunk_overrides_state_ck CHECK (override_state IN ('NONE', 'ACTIVE', 'NEEDS_REVIEW', 'DISCARDED')),
    CONSTRAINT chunk_overrides_source_ck CHECK (override_source = 'MANUAL'),
    CONSTRAINT chunk_overrides_reason_ck CHECK (length(reason) BETWEEN 1 AND 512),
    CONSTRAINT chunk_overrides_hash_ck CHECK (replaced_text_hash IS NULL OR replaced_text_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT chunk_overrides_identity_uq UNIQUE (id, space_id),
    CONSTRAINT chunk_overrides_key_uq UNIQUE (space_id, child_chunk_id, version_no),
    CONSTRAINT chunk_overrides_child_fk FOREIGN KEY (child_chunk_id, space_id)
        REFERENCES child_chunks (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT chunk_overrides_revision_fk FOREIGN KEY (document_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT
);

CREATE INDEX chunk_overrides_child_idx ON chunk_overrides (space_id, child_chunk_id, version_no DESC);
CREATE INDEX chunk_overrides_state_idx ON chunk_overrides (space_id, override_state, updated_at DESC);

CREATE TABLE index_versions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    index_state VARCHAR(32) NOT NULL DEFAULT 'BUILDING',
    candidate_collection VARCHAR(512) NOT NULL,
    embedding_profile_version VARCHAR(64) NOT NULL,
    chunking_strategy_version VARCHAR(64) NOT NULL,
    document_revision_count INTEGER NOT NULL DEFAULT 0,
    child_chunk_count INTEGER NOT NULL DEFAULT 0,
    validation_document_count INTEGER,
    validation_child_chunk_count INTEGER,
    validation_vector_dimension INTEGER,
    validation_orphan_child_count INTEGER,
    validation_sample_retrieval_passed BOOLEAN,
    validation_space_filter_passed BOOLEAN,
    validation_checked_at TIMESTAMPTZ,
    activated_at TIMESTAMPTZ,
    retired_at TIMESTAMPTZ,
    retention_deadline TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT index_versions_version_ck CHECK (version_no > 0),
    CONSTRAINT index_versions_state_ck CHECK (index_state IN ('BUILDING', 'VALIDATING', 'READY', 'ACTIVE', 'RETIRED', 'FAILED')),
    CONSTRAINT index_versions_counts_ck CHECK (document_revision_count >= 0 AND child_chunk_count >= 0),
    CONSTRAINT index_versions_validation_ck CHECK (
        (index_state = 'ACTIVE' AND validation_sample_retrieval_passed = TRUE AND validation_space_filter_passed = TRUE)
        OR index_state <> 'ACTIVE'),
    CONSTRAINT index_versions_activation_ck CHECK (
        (activated_at IS NOT NULL AND index_state IN ('ACTIVE', 'RETIRED'))
        OR activated_at IS NULL),
    CONSTRAINT index_versions_retention_ck CHECK (
        retention_deadline IS NULL
        OR (activated_at IS NOT NULL AND retention_deadline >= activated_at)),
    CONSTRAINT index_versions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT index_versions_key_uq UNIQUE (space_id, version_no)
);

CREATE INDEX index_versions_space_state_idx ON index_versions (space_id, index_state, version_no DESC);

CREATE TABLE active_index_pointers (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    active_index_version_id UUID NOT NULL,
    previous_index_version_id UUID,
    version_no INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT active_index_pointers_version_ck CHECK (version_no > 0),
    CONSTRAINT active_index_pointers_identity_uq UNIQUE (id, space_id),
    CONSTRAINT active_index_pointers_space_uq UNIQUE (space_id),
    CONSTRAINT active_index_pointers_active_fk FOREIGN KEY (active_index_version_id, space_id)
        REFERENCES index_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT active_index_pointers_previous_fk FOREIGN KEY (previous_index_version_id, space_id)
        REFERENCES index_versions (id, space_id) ON DELETE RESTRICT
);

CREATE TABLE retrieval_profiles (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    profile_id UUID NOT NULL,
    version_no INTEGER NOT NULL,
    dense_top_k INTEGER NOT NULL,
    bm25_top_k INTEGER NOT NULL,
    rrf_k INTEGER NOT NULL,
    rrf_dense_weight DOUBLE PRECISION NOT NULL,
    rrf_bm25_weight DOUBLE PRECISION NOT NULL,
    rerank_top_k INTEGER NOT NULL,
    max_context_children INTEGER NOT NULL,
    expansion_mode VARCHAR(32) NOT NULL,
    max_parents_per_child INTEGER NOT NULL,
    max_neighbors_per_parent INTEGER NOT NULL,
    max_context_tokens INTEGER NOT NULL,
    immutable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT retrieval_profiles_version_ck CHECK (version_no > 0),
    CONSTRAINT retrieval_profiles_tops_ck CHECK (
        dense_top_k BETWEEN 1 AND 100 AND bm25_top_k BETWEEN 1 AND 100 AND rerank_top_k BETWEEN 1 AND 100),
    CONSTRAINT retrieval_profiles_rrf_ck CHECK (
        rrf_k BETWEEN 1 AND 1000 AND rrf_dense_weight BETWEEN 0 AND 1 AND rrf_bm25_weight BETWEEN 0 AND 1),
    CONSTRAINT retrieval_profiles_context_ck CHECK (
        max_context_children BETWEEN 1 AND 20 AND max_context_tokens >= 0),
    CONSTRAINT retrieval_profiles_expansion_ck CHECK (expansion_mode IN ('NONE', 'PARENT', 'NEIGHBOR', 'PARENT_AND_NEIGHBOR')),
    CONSTRAINT retrieval_profiles_expansion_limits_ck CHECK (
        max_parents_per_child BETWEEN 0 AND 8 AND max_neighbors_per_parent BETWEEN 0 AND 16),
    CONSTRAINT retrieval_profiles_immutable_ck CHECK (immutable = TRUE),
    CONSTRAINT retrieval_profiles_identity_uq UNIQUE (id, space_id),
    CONSTRAINT retrieval_profiles_key_uq UNIQUE (space_id, profile_id, version_no)
);

CREATE INDEX retrieval_profiles_space_profile_idx ON retrieval_profiles (space_id, profile_id, version_no DESC);

CREATE TRIGGER retrieval_profiles_immutable_trg
    BEFORE UPDATE ON retrieval_profiles
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();

CREATE TABLE active_profile_pointers (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    active_profile_version_id UUID NOT NULL,
    active_version_no INTEGER NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT active_profile_pointers_identity_uq UNIQUE (id, space_id),
    CONSTRAINT active_profile_pointers_space_uq UNIQUE (space_id),
    CONSTRAINT active_profile_pointers_version_ck CHECK (active_version_no > 0),
    CONSTRAINT active_profile_pointers_profile_fk FOREIGN KEY (active_profile_version_id, space_id)
        REFERENCES retrieval_profiles (id, space_id) ON DELETE RESTRICT
);
