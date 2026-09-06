CREATE TABLE index_validation_results (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    index_version_id UUID NOT NULL,
    manifest_set_hash CHAR(64) NOT NULL,
    quality_policy_version VARCHAR(120) NOT NULL,
    outcome VARCHAR(8) NOT NULL,
    shadow BOOLEAN NOT NULL DEFAULT TRUE,
    checked_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT index_validation_results_hash_ck CHECK (manifest_set_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT index_validation_results_outcome_ck CHECK (outcome IN ('PASS', 'FAIL')),
    CONSTRAINT index_validation_results_shadow_ck CHECK (shadow = TRUE),
    CONSTRAINT index_validation_results_identity_uq UNIQUE (id, space_id),
    CONSTRAINT index_validation_results_index_policy_uq
        UNIQUE (space_id, index_version_id, quality_policy_version),
    CONSTRAINT index_validation_results_index_fk FOREIGN KEY (index_version_id, space_id)
        REFERENCES index_versions (id, space_id) ON DELETE RESTRICT
);

CREATE INDEX index_validation_results_space_checked_idx
    ON index_validation_results (space_id, checked_at DESC);

CREATE TRIGGER index_validation_results_immutable_trg
    BEFORE UPDATE ON index_validation_results
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
