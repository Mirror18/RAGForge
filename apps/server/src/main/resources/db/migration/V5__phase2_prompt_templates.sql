-- Phase 2 prompt-template ownership boundary.
-- Prompt versions are retained in V3; this migration adds the missing real
-- template shell and makes every version belong to one template in the same space.

CREATE TABLE prompt_templates (
    id UUID PRIMARY KEY,
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    correlation_id UUID NOT NULL,
    CONSTRAINT prompt_templates_purpose_ck CHECK (purpose IN ('CHAT', 'EMBEDDING', 'RERANK')),
    CONSTRAINT prompt_templates_identity_uq UNIQUE (id, space_id),
    CONSTRAINT prompt_templates_name_uq UNIQUE (space_id, name)
);

CREATE INDEX prompt_templates_space_created_idx
    ON prompt_templates (space_id, created_at DESC);

ALTER TABLE prompt_versions
    ADD COLUMN prompt_template_id UUID;

-- Preserve V3 data without inventing a cross-space relationship. Existing
-- prompt_key values become deterministic shell names in their owning space.
INSERT INTO prompt_templates (id, space_id, name, purpose, created_at, updated_at, correlation_id)
SELECT md5(space_id::text || ':' || prompt_key)::uuid,
       space_id,
       prompt_key,
       'CHAT',
       MIN(created_at),
       MAX(updated_at),
       md5(space_id::text || ':' || prompt_key || ':template')::uuid
FROM prompt_versions
GROUP BY space_id, prompt_key;

-- V4's trigger is intentionally state-only. Temporarily suspend it while the
-- new ownership column is backfilled, then restore the exact state machine.
DROP TRIGGER IF EXISTS prompt_versions_state_transition_trg ON prompt_versions;

UPDATE prompt_versions p
SET prompt_template_id = t.id
FROM prompt_templates t
WHERE t.space_id = p.space_id
  AND t.name = p.prompt_key;

ALTER TABLE prompt_versions
    ALTER COLUMN prompt_template_id SET NOT NULL,
    ADD CONSTRAINT prompt_versions_template_fk
        FOREIGN KEY (prompt_template_id, space_id)
        REFERENCES prompt_templates (id, space_id) ON DELETE RESTRICT;

CREATE INDEX prompt_versions_template_version_idx
    ON prompt_versions (space_id, prompt_template_id, version_no DESC);

CREATE OR REPLACE FUNCTION ragforge_prompt_version_state_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.space_id IS DISTINCT FROM NEW.space_id
       OR OLD.prompt_template_id IS DISTINCT FROM NEW.prompt_template_id
       OR OLD.prompt_key IS DISTINCT FROM NEW.prompt_key
       OR OLD.version_no IS DISTINCT FROM NEW.version_no
       OR OLD.template IS DISTINCT FROM NEW.template
       OR OLD.template_hash IS DISTINCT FROM NEW.template_hash
       OR OLD.variables_schema IS DISTINCT FROM NEW.variables_schema
       OR OLD.output_contract IS DISTINCT FROM NEW.output_contract
       OR OLD.change_note IS DISTINCT FROM NEW.change_note
       OR OLD.created_by_user_id IS DISTINCT FROM NEW.created_by_user_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
    THEN
        RAISE EXCEPTION 'prompt version % is immutable except for a state transition', OLD.id
            USING ERRCODE = '55000';
    END IF;

    IF OLD.status = 'DRAFT' AND NEW.status = 'PUBLISHED' THEN
        RETURN NEW;
    ELSIF OLD.status = 'PUBLISHED' AND NEW.status = 'RETIRED' THEN
        RETURN NEW;
    END IF;

    RAISE EXCEPTION 'invalid prompt version state transition % -> %', OLD.status, NEW.status
        USING ERRCODE = '55000';
END;
$$;

CREATE OR REPLACE FUNCTION ragforge_prompt_version_template_link()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    template_id UUID;
BEGIN
    IF NEW.prompt_template_id IS NULL THEN
        SELECT id INTO template_id
        FROM prompt_templates
        WHERE space_id = NEW.space_id AND name = NEW.prompt_key;

        IF template_id IS NULL THEN
            template_id := md5(NEW.space_id::text || ':' || NEW.prompt_key)::uuid;
            INSERT INTO prompt_templates
                (id, space_id, name, purpose, created_at, updated_at, correlation_id)
            VALUES
                (template_id, NEW.space_id, NEW.prompt_key, 'CHAT', NEW.created_at, NEW.updated_at,
                 md5(NEW.space_id::text || ':' || NEW.prompt_key || ':template')::uuid)
            ON CONFLICT (space_id, name) DO UPDATE SET name = EXCLUDED.name;
        END IF;
        NEW.prompt_template_id := template_id;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER prompt_versions_template_link_trg
    BEFORE INSERT ON prompt_versions
    FOR EACH ROW EXECUTE FUNCTION ragforge_prompt_version_template_link();

CREATE TRIGGER prompt_versions_state_transition_trg
    BEFORE UPDATE ON prompt_versions
    FOR EACH ROW EXECUTE FUNCTION ragforge_prompt_version_state_transition();
