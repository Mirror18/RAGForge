CREATE TABLE source_checkpoint_objects (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    source_id UUID NOT NULL,
    canonical_source_path VARCHAR(2048) NOT NULL,
    stable_source_object_id VARCHAR(512) NOT NULL,
    source_version VARCHAR(255) NOT NULL,
    content_hash CHAR(64) NOT NULL,
    byte_length BIGINT NOT NULL,
    media_type VARCHAR(160) NOT NULL,
    provenance VARCHAR(2048) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT source_checkpoint_objects_identity_uq UNIQUE (id, space_id),
    CONSTRAINT source_checkpoint_objects_key_uq UNIQUE (space_id, source_id, canonical_source_path),
    CONSTRAINT source_checkpoint_objects_source_fk FOREIGN KEY (source_id, space_id)
        REFERENCES sources (id, space_id) ON DELETE CASCADE,
    CONSTRAINT source_checkpoint_objects_path_ck CHECK (
        canonical_source_path !~ '^/' AND canonical_source_path !~ '^[A-Za-z]:'
        AND canonical_source_path !~ E'\\\\' AND canonical_source_path !~ '(^|/)\\.\\.?(/|$)'
    )
);

CREATE INDEX source_checkpoint_objects_source_idx
    ON source_checkpoint_objects (space_id, source_id, canonical_source_path);
