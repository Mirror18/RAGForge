ALTER TABLE provider_connections
    DROP CONSTRAINT provider_connections_type_ck;

ALTER TABLE provider_connections
    ADD CONSTRAINT provider_connections_type_ck
        CHECK (provider_type IN ('OLLAMA', 'OPENAI_COMPATIBLE', 'MIMO', 'AI_RUNTIME'));
