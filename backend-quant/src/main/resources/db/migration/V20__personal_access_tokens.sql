-- V2-M10 personal access tokens (PRD 11.4.11 F-MCP-2, 12.12): for the MCP server and other developer clients.
-- Only a SHA-256 of the token is stored; the token itself is shown once at creation.
CREATE TABLE personal_access_tokens (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID         NOT NULL REFERENCES users (id),
    name          VARCHAR(40)  NOT NULL,
    scope         VARCHAR(16)  NOT NULL,
    token_hash    CHAR(64)     NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at    TIMESTAMPTZ  NOT NULL,
    last_used_at  TIMESTAMPTZ,
    revoked_at    TIMESTAMPTZ,
    CONSTRAINT pat_scope_chk CHECK (scope IN ('read', 'read+propose')),
    CONSTRAINT pat_expiry_chk CHECK (expires_at > created_at AND expires_at <= created_at + INTERVAL '90 days 1 minute')
);
CREATE UNIQUE INDEX pat_token_hash_uq ON personal_access_tokens (token_hash);
CREATE INDEX pat_user_idx ON personal_access_tokens (user_id, created_at DESC);
