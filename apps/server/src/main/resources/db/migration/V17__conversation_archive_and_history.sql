-- Conversation history is retained as an auditable record. Archive is a soft state;
-- runs, answers and provenance remain immutable and space-scoped.
ALTER TABLE conversations
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN archived_at TIMESTAMPTZ,
    ADD COLUMN archived_by UUID REFERENCES users (id) ON DELETE SET NULL;

ALTER TABLE conversations
    ADD CONSTRAINT conversations_status_ck CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    ADD CONSTRAINT conversations_archive_fields_ck CHECK (
        (status = 'ACTIVE' AND archived_at IS NULL AND archived_by IS NULL)
        OR (status = 'ARCHIVED' AND archived_at IS NOT NULL)
    );

CREATE INDEX conversations_space_status_updated_idx
    ON conversations (space_id, status, updated_at DESC);
