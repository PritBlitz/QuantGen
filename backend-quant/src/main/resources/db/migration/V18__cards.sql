-- V2-M9 virtual cards and the merchant sandbox (PRD 11.4.10, 12.11; ARCHITECTURE 12.12; D-032).

ALTER TABLE journal_entries DROP CONSTRAINT journal_entries_type_chk;
ALTER TABLE journal_entries ADD CONSTRAINT journal_entries_type_chk CHECK (type IN
    ('DEPOSIT', 'TRANSFER', 'TRADE', 'LOAN_DISBURSAL', 'LOAN_REPAYMENT', 'REVERSAL', 'BILL_PAYMENT', 'TOPUP',
     'INTEREST', 'CARD', 'REFUND'));

-- Captured card payments, owed onwards to merchants.
INSERT INTO ledger_accounts (id, kind, code, normal_side) VALUES
    ('00000000-0000-0000-0000-000000000008', 'SYSTEM', 'CARD_CLEARING', 'CREDIT');

-- One virtual debit card per everyday account. The PAN is AES-GCM encrypted (FieldCrypto) and found by its blind
-- index; only the last 4 digits are stored in clear. The CVV is never stored (HMAC of the PAN and a 30 s window).
CREATE TABLE cards (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID          NOT NULL REFERENCES users (id),
    account_id          UUID          NOT NULL REFERENCES accounts (id),
    pan_enc             VARCHAR(255)  NOT NULL,
    pan_index           CHAR(64)      NOT NULL,
    last4               CHAR(4)       NOT NULL,
    expiry              CHAR(5)       NOT NULL,
    frozen              BOOLEAN       NOT NULL DEFAULT FALSE,
    online              BOOLEAN       NOT NULL DEFAULT TRUE,
    per_txn_limit       NUMERIC(19,4) NOT NULL,
    blocked_categories  VARCHAR(200)  NOT NULL DEFAULT '',
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT cards_limit_chk CHECK (per_txn_limit > 0),
    CONSTRAINT cards_expiry_chk CHECK (expiry ~ '^(0[1-9]|1[0-2])/[0-9]{2}$')
);
CREATE UNIQUE INDEX cards_account_uq ON cards (account_id);
CREATE UNIQUE INDEX cards_pan_index_uq ON cards (pan_index);
CREATE INDEX cards_user_idx ON cards (user_id, created_at);

-- Every sandbox authorization attempt, approved or declined. merchant_user_id is the signed-in user who ran the
-- checkout: only they can capture or refund it.
CREATE TABLE card_authorizations (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    card_id           UUID          REFERENCES cards (id),
    merchant_user_id  UUID          NOT NULL REFERENCES users (id),
    merchant          VARCHAR(40)   NOT NULL,
    category          VARCHAR(16)   NOT NULL,
    amount            NUMERIC(19,4) NOT NULL,
    status            VARCHAR(10)   NOT NULL,
    decline_reason    VARCHAR(60),
    hold_id           UUID REFERENCES holds (id),
    captured_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
    refunded_amount   NUMERIC(19,4) NOT NULL DEFAULT 0,
    capture_entry_id  UUID REFERENCES journal_entries (id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    captured_at       TIMESTAMPTZ,
    CONSTRAINT card_auth_status_chk CHECK (status IN ('AUTHORIZED', 'DECLINED', 'CAPTURED', 'REFUNDED', 'EXPIRED')),
    CONSTRAINT card_auth_category_chk CHECK (category IN
        ('GROCERY', 'DINING', 'TRAVEL', 'ELECTRONICS', 'GAMBLING', 'FUEL', 'ENTERTAINMENT', 'OTHER')),
    CONSTRAINT card_auth_amount_chk CHECK (amount > 0),
    CONSTRAINT card_auth_declined_chk CHECK ((status = 'DECLINED') = (decline_reason IS NOT NULL)),
    CONSTRAINT card_auth_hold_chk CHECK (status = 'DECLINED' OR hold_id IS NOT NULL),
    CONSTRAINT card_auth_capture_chk CHECK (captured_amount >= 0 AND captured_amount <= amount
        AND refunded_amount >= 0 AND refunded_amount <= captured_amount),
    CONSTRAINT card_auth_captured_chk CHECK ((status IN ('CAPTURED', 'REFUNDED')) = (capture_entry_id IS NOT NULL))
);
CREATE INDEX card_auth_card_idx ON card_authorizations (card_id, created_at);
CREATE INDEX card_auth_capture_entry_idx ON card_authorizations (capture_entry_id);

-- Each (partial) refund of a captured payment: a REFUND entry CARD_CLEARING → customer.
CREATE TABLE card_refunds (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    authorization_id  UUID          NOT NULL REFERENCES card_authorizations (id),
    amount            NUMERIC(19,4) NOT NULL,
    journal_entry_id  UUID          NOT NULL REFERENCES journal_entries (id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT card_refunds_amount_chk CHECK (amount > 0)
);
CREATE INDEX card_refunds_auth_idx ON card_refunds (authorization_id);
CREATE UNIQUE INDEX card_refunds_entry_uq ON card_refunds (journal_entry_id);
