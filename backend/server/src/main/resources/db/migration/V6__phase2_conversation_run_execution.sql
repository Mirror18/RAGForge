-- Phase 2 no-RAG conversation ownership and run execution linkage.
-- This migration intentionally has no dependency on the not-yet-merged model worker.
CREATE TABLE conversations (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    actor_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    title VARCHAR(200) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT conversations_identity_uq UNIQUE (id, space_id)
);

CREATE INDEX conversations_space_created_idx ON conversations (space_id, created_at DESC);

ALTER TABLE runs ADD COLUMN conversation_id UUID;
ALTER TABLE runs ADD CONSTRAINT runs_conversation_fk
    FOREIGN KEY (conversation_id, space_id) REFERENCES conversations (id, space_id) ON DELETE RESTRICT;
CREATE INDEX runs_space_conversation_created_idx
    ON runs (space_id, conversation_id, created_at DESC);
