CREATE TABLE users (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(120) NOT NULL,
    platform_role VARCHAR(32) NOT NULL DEFAULT 'USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT users_platform_role_ck CHECK (platform_role IN ('USER', 'PLATFORM_ADMIN'))
);

CREATE UNIQUE INDEX users_email_lower_uq ON users (LOWER(email));

CREATE TABLE sessions (
    id UUID PRIMARY KEY,
    token_hash CHAR(64) NOT NULL UNIQUE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    csrf_token CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT sessions_expiry_ck CHECK (expires_at > created_at)
);

CREATE INDEX sessions_user_id_idx ON sessions (user_id);
CREATE INDEX sessions_active_idx ON sessions (token_hash, expires_at) WHERE revoked_at IS NULL;

CREATE TABLE knowledge_spaces (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(2000),
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT knowledge_spaces_status_ck CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE UNIQUE INDEX knowledge_spaces_name_lower_uq ON knowledge_spaces (LOWER(name));

CREATE TABLE space_memberships (
    space_id UUID NOT NULL REFERENCES knowledge_spaces (id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (space_id, user_id),
    CONSTRAINT space_memberships_role_ck CHECK (role IN ('SPACE_ADMIN', 'EDITOR', 'VIEWER'))
);

CREATE INDEX space_memberships_user_id_idx ON space_memberships (user_id, space_id);

CREATE TABLE audit_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    actor_user_id UUID REFERENCES users (id) ON DELETE SET NULL,
    space_id UUID REFERENCES knowledge_spaces (id) ON DELETE SET NULL,
    aggregate_id UUID,
    correlation_id UUID NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX audit_events_space_time_idx ON audit_events (space_id, occurred_at DESC);
CREATE INDEX audit_events_correlation_idx ON audit_events (correlation_id);

CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    aggregate_id UUID,
    space_id UUID REFERENCES knowledge_spaces (id) ON DELETE SET NULL,
    correlation_id UUID NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(500)
);

CREATE INDEX outbox_events_unpublished_idx ON outbox_events (occurred_at) WHERE published_at IS NULL;
CREATE INDEX outbox_events_space_idx ON outbox_events (space_id, occurred_at DESC);
