-- V2-M1 Security 2.0 (PRD 11.4.2, ARCHITECTURE 12.5): transaction PIN, step-up tokens, user limits, KYC.

-- Transaction PIN (F-SEC-3). pin_hash is BCrypt over a keyed digest of the PIN, never the PIN itself.
CREATE TABLE user_pins (
    user_id       UUID PRIMARY KEY REFERENCES users (id),
    pin_hash      VARCHAR(72) NOT NULL,
    failed_count  INT         NOT NULL DEFAULT 0,
    locked_until  TIMESTAMPTZ,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Step-up tokens (F-SEC-2, D-029): random, stored hashed, bound to (user, action), short-lived.
CREATE TABLE step_up_tokens (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id),
    action      VARCHAR(24) NOT NULL,
    method      VARCHAR(8)  NOT NULL,
    token_hash  CHAR(64)    NOT NULL UNIQUE,
    expires_at  TIMESTAMPTZ NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT step_up_tokens_method_chk CHECK (method IN ('PIN', 'PASSKEY'))
);
CREATE INDEX step_up_tokens_expiry_idx ON step_up_tokens (expires_at);

-- Customer-chosen limits (F-SEC-7). NULL means "use the platform maximum".
CREATE TABLE user_limits (
    user_id      UUID PRIMARY KEY REFERENCES users (id),
    per_transfer NUMERIC(19,2),
    daily        NUMERIC(19,2),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT user_limits_per_transfer_chk CHECK (per_transfer IS NULL OR per_transfer >= 1),
    CONSTRAINT user_limits_daily_chk CHECK (daily IS NULL OR daily >= 1)
);

-- KYC profile (F-SEC-6, D-032). PAN and address are AES-256-GCM ciphertext; pan_idx is an HMAC blind index so a
-- PAN can be unique without storing it in the clear. A row without PAN is a grandfathered user.
CREATE TABLE kyc_profiles (
    user_id        UUID PRIMARY KEY REFERENCES users (id),
    status         VARCHAR(10) NOT NULL,
    pan_enc        TEXT,
    pan_idx        CHAR(64) UNIQUE,
    pan_last4      CHAR(4),
    date_of_birth  DATE,
    address_enc    TEXT,
    verified_at    TIMESTAMPTZ,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT kyc_profiles_status_chk CHECK (status IN ('PENDING', 'VERIFIED')),
    CONSTRAINT kyc_profiles_pan_chk CHECK ((pan_enc IS NULL) = (pan_idx IS NULL))
);

-- Existing users are grandfathered as VERIFIED (F-SEC-6).
INSERT INTO kyc_profiles (user_id, status, verified_at)
SELECT id, 'VERIFIED', now() FROM users;
