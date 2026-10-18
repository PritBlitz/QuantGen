-- V2-M2 Payments 2.0, part 5 (PRD 7.9, F-PAY-10): Razorpay test-mode top-ups and webhook de-duplication.

CREATE TABLE topups (
    id                  UUID PRIMARY KEY,
    user_id             UUID          NOT NULL REFERENCES users (id),
    account_id          UUID          NOT NULL REFERENCES accounts (id),
    amount              NUMERIC(19,4) NOT NULL,
    status              VARCHAR(10)   NOT NULL DEFAULT 'CREATED',
    gateway_order_id    VARCHAR(40)   NOT NULL,
    gateway_payment_id  VARCHAR(40),
    journal_entry_id    UUID REFERENCES journal_entries (id),
    transaction_id      UUID REFERENCES ledger_lines (id),
    failure_reason      VARCHAR(200),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT topups_amount_chk CHECK (amount > 0),
    CONSTRAINT topups_status_chk CHECK (status IN ('CREATED', 'CREDITED', 'FAILED', 'EXPIRED')),
    -- Credited exactly when the ledger entry exists.
    CONSTRAINT topups_credited_chk CHECK ((status = 'CREDITED') = (journal_entry_id IS NOT NULL))
);
CREATE UNIQUE INDEX topups_order_uq ON topups (gateway_order_id);
CREATE UNIQUE INDEX topups_payment_uq ON topups (gateway_payment_id);
CREATE INDEX topups_account_idx ON topups (account_id, created_at DESC);
CREATE INDEX topups_pending_idx ON topups (created_at) WHERE status = 'CREATED';

-- Razorpay retries webhooks; an event id is processed once (recorded in the same transaction as its effect).
CREATE TABLE webhook_events (
    provider     VARCHAR(16) NOT NULL,
    event_id     VARCHAR(64) NOT NULL,
    event_type   VARCHAR(40) NOT NULL,
    received_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_id)
);
