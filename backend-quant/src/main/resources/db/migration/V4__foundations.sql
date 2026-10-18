-- V2-M1 foundations (PRD §11.4.1, ARCHITECTURE §12.3-12.4): outbox, holds, feature flags.

-- Transactional outbox (D-028): written by LedgerService.post in the same transaction as the entry.
CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type          VARCHAR(40)  NOT NULL,
    aggregate_id  UUID,
    payload       JSONB        NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    processed_at  TIMESTAMPTZ,
    attempts      SMALLINT     NOT NULL DEFAULT 0,
    last_error    VARCHAR(200)
);
CREATE INDEX outbox_events_pending_idx ON outbox_events (created_at, id) WHERE processed_at IS NULL;

-- Idempotent consumers: a consumer records (consumer, event) in the same transaction as its own work.
CREATE TABLE consumer_offsets (
    consumer      VARCHAR(40) NOT NULL,
    event_id      UUID        NOT NULL REFERENCES outbox_events (id),
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);

-- Holds earmark money without moving it (D-027): available = balance - SUM(ACTIVE holds).
CREATE TABLE holds (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_account_id  UUID          NOT NULL REFERENCES ledger_accounts (id),
    amount             NUMERIC(19,4) NOT NULL,
    status             VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    reason             VARCHAR(32)   NOT NULL,
    reference          VARCHAR(80),
    expires_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ,
    journal_entry_id   UUID REFERENCES journal_entries (id),
    CONSTRAINT holds_amount_chk CHECK (amount > 0),
    CONSTRAINT holds_status_chk CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT holds_resolved_chk CHECK ((status = 'ACTIVE') = (resolved_at IS NULL))
);
CREATE INDEX holds_active_idx ON holds (ledger_account_id) WHERE status = 'ACTIVE';
CREATE INDEX holds_expiry_idx ON holds (expires_at) WHERE status = 'ACTIVE' AND expires_at IS NOT NULL;

-- Feature flags (F-FLAG-1): every v2 feature is off until it ships and an admin switches it on.
CREATE TABLE feature_flags (
    name        VARCHAR(32) PRIMARY KEY,
    enabled     BOOLEAN     NOT NULL DEFAULT FALSE,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  UUID REFERENCES users (id)
);
INSERT INTO feature_flags (name, enabled) VALUES
    ('ai', FALSE), ('autopilot', FALSE), ('razorpay', FALSE), ('cards', FALSE), ('mcp', FALSE);
