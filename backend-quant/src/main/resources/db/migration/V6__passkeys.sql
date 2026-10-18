-- V2-M1 passkeys (F-SEC-1, D-029): WebAuthn credentials, per-user user handles, and short-lived challenges.

-- The WebAuthn "user handle": 32 random bytes per user, unrelated to the user id, so a discoverable credential
-- never carries anything guessable.
CREATE TABLE webauthn_users (
    user_id      UUID PRIMARY KEY REFERENCES users (id),
    user_handle  BYTEA NOT NULL UNIQUE
);

CREATE TABLE passkeys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID         NOT NULL REFERENCES users (id),
    credential_id    BYTEA        NOT NULL UNIQUE,
    public_key_cose  BYTEA        NOT NULL,
    sign_count       BIGINT       NOT NULL DEFAULT 0,
    name             VARCHAR(40)  NOT NULL,
    transports       VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at     TIMESTAMPTZ
);
CREATE INDEX passkeys_user_idx ON passkeys (user_id);

-- One row per outstanding challenge, consumed (deleted) when used, so a challenge can never be replayed.
CREATE TABLE webauthn_challenges (
    challenge     VARCHAR(100) PRIMARY KEY,
    purpose       VARCHAR(12)  NOT NULL,
    user_id       UUID REFERENCES users (id),
    action        VARCHAR(24),
    request_json  TEXT         NOT NULL,
    expires_at    TIMESTAMPTZ  NOT NULL,
    CONSTRAINT webauthn_challenges_purpose_chk CHECK (purpose IN ('REGISTER', 'LOGIN', 'STEP_UP'))
);
CREATE INDEX webauthn_challenges_expiry_idx ON webauthn_challenges (expires_at);
