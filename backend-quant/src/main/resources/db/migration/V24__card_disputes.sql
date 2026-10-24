-- F-CARD-4 card disputes (PRD 12.11). A cardholder disputes (part of) a captured payment; an admin refunds or
-- rejects it. A refund is a REFUND entry CARD_CLEARING → customer, recorded in card_refunds like a sandbox refund.
CREATE TABLE card_disputes (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id            UUID          NOT NULL REFERENCES cards (id),
    authorization_id   UUID          NOT NULL REFERENCES card_authorizations (id),
    user_id            UUID          NOT NULL REFERENCES users (id),
    amount             NUMERIC(19,4) NOT NULL,
    reason             VARCHAR(20)   NOT NULL,
    note               VARCHAR(280),
    status             VARCHAR(10)   NOT NULL DEFAULT 'OPEN',
    resolution_reason  VARCHAR(280),
    resolved_by        UUID REFERENCES users (id),
    refund_entry_id    UUID REFERENCES journal_entries (id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    resolved_at        TIMESTAMPTZ,
    CONSTRAINT card_disputes_amount_chk CHECK (amount > 0),
    CONSTRAINT card_disputes_reason_chk CHECK (reason IN
        ('NOT_RECEIVED', 'NOT_AS_DESCRIBED', 'DUPLICATE', 'NOT_RECOGNISED', 'OTHER')),
    CONSTRAINT card_disputes_status_chk CHECK (status IN ('OPEN', 'REFUNDED', 'REJECTED')),
    CONSTRAINT card_disputes_resolved_chk CHECK ((status = 'OPEN') = (resolved_at IS NULL)),
    CONSTRAINT card_disputes_refund_chk CHECK ((status = 'REFUNDED') = (refund_entry_id IS NOT NULL))
);
-- One open dispute per payment.
CREATE UNIQUE INDEX card_disputes_open_uq ON card_disputes (authorization_id) WHERE status = 'OPEN';
CREATE INDEX card_disputes_user_idx ON card_disputes (user_id, created_at);
CREATE INDEX card_disputes_status_idx ON card_disputes (status, created_at);
