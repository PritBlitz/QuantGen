-- V2-M2 Payments 2.0, part 1 (PRD 11.4.3 F-PAY-5/6/8, 12.4): handles, payment requests, transfer source.

-- F-PAY-5: one handle per user ("name@qg"), pointing at one of the user's accounts.
CREATE TABLE handles (
    user_id     UUID        PRIMARY KEY REFERENCES users (id),
    handle      VARCHAR(33) NOT NULL,
    account_id  UUID        NOT NULL REFERENCES accounts (id),
    changed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT handles_format_chk CHECK (handle ~ '^[a-z0-9.]{3,30}@qg$')
);
CREATE UNIQUE INDEX handles_handle_uq ON handles (handle);

-- A handle given up stays reserved for its old owner for 30 days, so nobody can take it over and receive
-- payments meant for that person (payers' saved handles and printed QR codes keep pointing at it).
CREATE TABLE retired_handles (
    handle      VARCHAR(33) PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES users (id),
    retired_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- F-PAY-8: "from" asks "to" for money. Handles are copied at creation for display; user ids decide access.
CREATE TABLE payment_requests (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    requester_user_id  UUID          NOT NULL REFERENCES users (id),
    payer_user_id      UUID          NOT NULL REFERENCES users (id),
    from_handle        VARCHAR(33)   NOT NULL,
    to_handle          VARCHAR(33)   NOT NULL,
    amount             NUMERIC(19,4) NOT NULL,
    note               VARCHAR(140),
    status             VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    transfer_id        UUID REFERENCES transfers (id),
    expires_at         TIMESTAMPTZ   NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT payment_requests_amount_chk CHECK (amount > 0),
    CONSTRAINT payment_requests_self_chk CHECK (requester_user_id <> payer_user_id),
    CONSTRAINT payment_requests_status_chk CHECK (status IN ('OPEN', 'PAID', 'DECLINED', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT payment_requests_paid_chk CHECK ((status = 'PAID') = (transfer_id IS NOT NULL))
);
CREATE INDEX payment_requests_requester_idx ON payment_requests (requester_user_id, created_at DESC);
CREATE INDEX payment_requests_payer_idx ON payment_requests (payer_user_id, created_at DESC);
CREATE INDEX payment_requests_open_expiry_idx ON payment_requests (expires_at) WHERE status = 'OPEN';

-- "Decline and block": the blocker never sees requests from the blocked user again.
CREATE TABLE blocked_requesters (
    user_id          UUID        NOT NULL REFERENCES users (id),
    blocked_user_id  UUID        NOT NULL REFERENCES users (id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, blocked_user_id)
);

-- PRD 12.2: TransferDto.source.
ALTER TABLE transfers ADD COLUMN source VARCHAR(10) NOT NULL DEFAULT 'MANUAL';
ALTER TABLE transfers ADD CONSTRAINT transfers_source_chk
    CHECK (source IN ('MANUAL', 'SCHEDULED', 'AUTOPILOT', 'INTENT', 'REQUEST'));
