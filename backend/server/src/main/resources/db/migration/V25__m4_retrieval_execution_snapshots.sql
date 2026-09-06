CREATE TABLE retrieval_execution_snapshots (
    snapshot_id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    schema_version VARCHAR(64) NOT NULL,
    execution_key VARCHAR(160) NOT NULL,
    index_version_id UUID NOT NULL,
    profile_id UUID NOT NULL,
    profile_version INTEGER NOT NULL,
    effective_parameters_hash CHAR(64) NOT NULL,
    effective_parameters_json JSONB NOT NULL,
    route_version VARCHAR(160) NOT NULL,
    executor_version VARCHAR(120) NOT NULL,
    degradation_policy VARCHAR(120) NOT NULL,
    evidence_bundle_id UUID NOT NULL,
    evidence_bundle_version INTEGER NOT NULL,
    evidence_bundle_ref VARCHAR(512) NOT NULL,
    dataset_hash CHAR(64) NOT NULL,
    config_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT retrieval_execution_snapshots_schema_ck
        CHECK (schema_version = 'retrieval-execution-snapshot.v1'),
    CONSTRAINT retrieval_execution_snapshots_profile_version_ck CHECK (profile_version > 0),
    CONSTRAINT retrieval_execution_snapshots_effective_hash_ck CHECK (effective_parameters_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT retrieval_execution_snapshots_evidence_version_ck CHECK (evidence_bundle_version > 0),
    CONSTRAINT retrieval_execution_snapshots_dataset_hash_ck CHECK (dataset_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT retrieval_execution_snapshots_config_hash_ck CHECK (config_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT retrieval_execution_snapshots_identity_uq UNIQUE (snapshot_id, space_id),
    CONSTRAINT retrieval_execution_snapshots_execution_uq UNIQUE (space_id, execution_key),
    CONSTRAINT retrieval_execution_snapshots_index_fk FOREIGN KEY (index_version_id, space_id)
        REFERENCES index_versions (id, space_id) ON DELETE RESTRICT
);

CREATE INDEX retrieval_execution_snapshots_space_created_idx
    ON retrieval_execution_snapshots (space_id, created_at DESC);

CREATE TRIGGER retrieval_execution_snapshots_immutable_trg
    BEFORE UPDATE ON retrieval_execution_snapshots
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
