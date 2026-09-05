-- Phase 2 prompt publication state machine.
-- V3 attached the generic immutable trigger to prompt_versions, which also
-- rejected the state transitions needed to publish and retire a version.

DROP TRIGGER IF EXISTS prompt_versions_immutable_trg ON prompt_versions;

CREATE OR REPLACE FUNCTION ragforge_prompt_version_state_transition()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- The prompt version identity, content, ownership, and creation audit
    -- fields are immutable. Only status, updated_at, and correlation_id may
    -- change during an accepted state transition.
    IF OLD.id IS DISTINCT FROM NEW.id
       OR OLD.space_id IS DISTINCT FROM NEW.space_id
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

CREATE TRIGGER prompt_versions_state_transition_trg
    BEFORE UPDATE ON prompt_versions
    FOR EACH ROW EXECUTE FUNCTION ragforge_prompt_version_state_transition();
