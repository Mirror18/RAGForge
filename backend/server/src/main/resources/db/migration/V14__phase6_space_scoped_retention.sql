-- Phase 6: every retention mutation carries an explicit tenant space.
DROP FUNCTION IF EXISTS ragforge_purge_expired_answers(TIMESTAMPTZ);

CREATE OR REPLACE FUNCTION ragforge_purge_expired_answers(p_space_id UUID, p_now TIMESTAMPTZ)
RETURNS INTEGER
LANGUAGE plpgsql
AS $$
DECLARE
    deleted_count INTEGER;
BEGIN
    DELETE FROM rag_answers
    WHERE space_id = p_space_id
      AND retention_deadline < p_now;
    GET DIAGNOSTICS deleted_count = ROW_COUNT;
    RETURN deleted_count;
END;
$$;
