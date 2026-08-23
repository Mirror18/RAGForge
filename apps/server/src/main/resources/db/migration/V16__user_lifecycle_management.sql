ALTER TABLE users
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE users
    ADD CONSTRAINT users_status_ck CHECK (status IN ('ACTIVE', 'DISABLED'));

CREATE INDEX users_status_created_idx ON users (status, created_at, id);
