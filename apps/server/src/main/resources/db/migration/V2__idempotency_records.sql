CREATE TABLE idempotency_records (
    principal_scope VARCHAR(255) NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    method VARCHAR(16) NOT NULL,
    request_path VARCHAR(512) NOT NULL,
    status_code INTEGER,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (principal_scope, idempotency_key),
    CONSTRAINT idempotency_records_hash_ck CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT idempotency_records_status_ck CHECK (status_code IS NULL OR status_code BETWEEN 100 AND 599)
);

CREATE INDEX idempotency_records_created_idx ON idempotency_records (created_at);
