-- Phase 2 space binding aggregate.
--
-- The model and prompt binding rows remain the immutable, versioned source of
-- the selected route/prompt references. This table stores the aggregate
-- version and the explicit cloud-egress decision that joins those rows.
-- Cloud authorization is deliberately metadata only: no bearer token, API key,
-- credential, signature, or raw prompt is persisted here.

ALTER TABLE space_model_bindings
    ADD CONSTRAINT space_model_bindings_identity_uq UNIQUE (id, space_id);

ALTER TABLE space_prompt_bindings
    ADD CONSTRAINT space_prompt_bindings_identity_uq UNIQUE (id, space_id);

CREATE TABLE space_binding_versions (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    version_no INTEGER NOT NULL,
    chat_model_binding_id UUID NOT NULL,
    embedding_model_binding_id UUID NOT NULL,
    rerank_model_binding_id UUID NOT NULL,
    prompt_binding_id UUID NOT NULL,
    cloud_egress_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    cloud_approval_id UUID,
    cloud_approved_by UUID,
    cloud_approved_at TIMESTAMPTZ,
    cloud_expires_at TIMESTAMPTZ,
    cloud_scope VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT space_binding_versions_version_ck CHECK (version_no > 0),
    CONSTRAINT space_binding_versions_identity_uq UNIQUE (id, space_id),
    CONSTRAINT space_binding_versions_version_uq UNIQUE (space_id, version_no),
    CONSTRAINT space_binding_versions_chat_fk FOREIGN KEY (chat_model_binding_id, space_id)
        REFERENCES space_model_bindings (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT space_binding_versions_embedding_fk FOREIGN KEY (embedding_model_binding_id, space_id)
        REFERENCES space_model_bindings (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT space_binding_versions_rerank_fk FOREIGN KEY (rerank_model_binding_id, space_id)
        REFERENCES space_model_bindings (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT space_binding_versions_prompt_fk FOREIGN KEY (prompt_binding_id, space_id)
        REFERENCES space_prompt_bindings (id, space_id) ON DELETE RESTRICT,
    CONSTRAINT space_binding_versions_scope_ck CHECK (
        cloud_scope IS NULL OR cloud_scope IN ('CHAT', 'EMBEDDING', 'RERANK', 'ALL')
    ),
    CONSTRAINT space_binding_versions_authorization_ck CHECK (
        (cloud_egress_enabled = FALSE
            AND cloud_approval_id IS NULL
            AND cloud_approved_by IS NULL
            AND cloud_approved_at IS NULL
            AND cloud_expires_at IS NULL
            AND cloud_scope IS NULL)
        OR
        (cloud_egress_enabled = TRUE
            AND cloud_approval_id IS NOT NULL
            AND cloud_approved_by IS NOT NULL
            AND cloud_approved_at IS NOT NULL
            AND cloud_expires_at IS NOT NULL
            AND cloud_scope IS NOT NULL
            AND cloud_expires_at > cloud_approved_at)
    )
);

ALTER TABLE space_binding_versions
    ADD CONSTRAINT space_binding_versions_approver_membership_fk
        FOREIGN KEY (space_id, cloud_approved_by) REFERENCES space_memberships (space_id, user_id)
        ON DELETE RESTRICT;

CREATE INDEX space_binding_versions_space_version_idx
    ON space_binding_versions (space_id, version_no DESC);

CREATE TRIGGER space_binding_versions_immutable_trg
    BEFORE UPDATE ON space_binding_versions
    FOR EACH ROW EXECUTE FUNCTION ragforge_reject_immutable_update();
