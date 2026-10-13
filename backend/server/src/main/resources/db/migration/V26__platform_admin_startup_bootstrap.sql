CREATE TABLE platform_admin_startup_tokens (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    token_hash CHAR(64) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL
);
