CREATE TABLE artifact_manifests (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    document_revision_id UUID NOT NULL,
    pipeline_version_id UUID NOT NULL,
    parent_artifact_id UUID NOT NULL,
    object_artifact_id UUID NOT NULL,
    content_hash CHAR(64) NOT NULL,
    object_ref VARCHAR(1024) NOT NULL,
    parser_name VARCHAR(120) NOT NULL,
    parser_version VARCHAR(120) NOT NULL,
    location_mapping_version VARCHAR(64) NOT NULL,
    immutable BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT artifact_manifests_hash_ck CHECK (content_hash ~ '^[0-9a-fA-F]{64}$'),
    CONSTRAINT artifact_manifests_object_ref_ck CHECK (object_ref ~ '^spaces/[0-9a-fA-F-]{36}/sources/[0-9a-fA-F-]{36}/revisions/[0-9a-fA-F-]{36}/artifacts/[0-9a-fA-F-]{36}/sha256/[0-9a-fA-F]{64}$'),
    CONSTRAINT artifact_manifests_immutable_ck CHECK (immutable = TRUE),
    CONSTRAINT artifact_manifests_identity_uq UNIQUE (id, space_id),
    CONSTRAINT artifact_manifests_lineage_uq UNIQUE (space_id, document_revision_id, pipeline_version_id, parser_name, parser_version),
    CONSTRAINT artifact_manifests_revision_fk FOREIGN KEY (document_revision_id, space_id)
        REFERENCES document_revisions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT artifact_manifests_pipeline_fk FOREIGN KEY (pipeline_version_id, space_id)
        REFERENCES pipeline_versions (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT artifact_manifests_parent_artifact_fk FOREIGN KEY (parent_artifact_id, space_id)
        REFERENCES artifacts (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT artifact_manifests_object_artifact_fk FOREIGN KEY (object_artifact_id, space_id)
        REFERENCES artifacts (id, space_id) ON DELETE RESTRICT
);

CREATE INDEX artifact_manifests_space_revision_idx
    ON artifact_manifests (space_id, document_revision_id, created_at DESC);

CREATE TRIGGER artifact_manifests_immutable_trg
    BEFORE UPDATE ON artifact_manifests
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
