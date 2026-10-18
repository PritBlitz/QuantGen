-- V2-M2 Payments 2.0, part 3 (PRD 11.4.3 F-PAY-11, 12.4): simulated billers and bill payments.
-- Also adds the journal types and system accounts that bill payments and gateway top-ups post with.

ALTER TABLE journal_entries DROP CONSTRAINT journal_entries_type_chk;
ALTER TABLE journal_entries ADD CONSTRAINT journal_entries_type_chk CHECK (type IN
    ('DEPOSIT', 'TRANSFER', 'TRADE', 'LOAN_DISBURSAL', 'LOAN_REPAYMENT', 'REVERSAL', 'BILL_PAYMENT', 'TOPUP'));

-- Money paid to billers (owed onwards to them) and money collected by the payment gateway (owed to customers).
INSERT INTO ledger_accounts (id, kind, code, normal_side) VALUES
    ('00000000-0000-0000-0000-000000000005', 'SYSTEM', 'BILLER_CLEARING',  'CREDIT'),
    ('00000000-0000-0000-0000-000000000006', 'SYSTEM', 'GATEWAY_CLEARING', 'DEBIT');

-- Fictional billers: the sandbox never names a real company.
CREATE TABLE billers (
    id                       UUID PRIMARY KEY,
    name                     VARCHAR(60) NOT NULL,
    category                 VARCHAR(16) NOT NULL,
    consumer_number_label    VARCHAR(40) NOT NULL,
    consumer_number_pattern  VARCHAR(60) NOT NULL,
    CONSTRAINT billers_category_chk CHECK (category IN
        ('ELECTRICITY', 'MOBILE', 'BROADBAND', 'DTH', 'GAS', 'WATER', 'CREDIT_CARD', 'INSURANCE'))
);
INSERT INTO billers (id, name, category, consumer_number_label, consumer_number_pattern) VALUES
    ('b1000000-0000-4000-8000-000000000001', 'Metro Power Distribution', 'ELECTRICITY', 'Account ID',        '^[0-9]{10}$'),
    ('b1000000-0000-4000-8000-000000000002', 'Sunline Electricity',      'ELECTRICITY', 'Consumer number',   '^[0-9]{12}$'),
    ('b1000000-0000-4000-8000-000000000003', 'Orbit Mobile Postpaid',    'MOBILE',      'Mobile number',     '^[6-9][0-9]{9}$'),
    ('b1000000-0000-4000-8000-000000000004', 'Nimbus Mobile Prepaid',    'MOBILE',      'Mobile number',     '^[6-9][0-9]{9}$'),
    ('b1000000-0000-4000-8000-000000000005', 'Lightwave Fibre',          'BROADBAND',   'Account number',    '^[0-9]{8,10}$'),
    ('b1000000-0000-4000-8000-000000000006', 'Skydish DTH',              'DTH',         'Subscriber ID',     '^[0-9]{10}$'),
    ('b1000000-0000-4000-8000-000000000007', 'Blueflame Gas',            'GAS',         'Consumer number',   '^[0-9]{9,17}$'),
    ('b1000000-0000-4000-8000-000000000008', 'City Water Board',         'WATER',       'RR number',         '^[A-Z0-9]{6,12}$'),
    ('b1000000-0000-4000-8000-000000000009', 'Northstar Credit Card',    'CREDIT_CARD', 'Card last 4 + mobile', '^[0-9]{4}[6-9][0-9]{9}$'),
    ('b1000000-0000-4000-8000-000000000010', 'Evergreen Life Insurance', 'INSURANCE',   'Policy number',     '^[0-9]{9}$'),
    ('b1000000-0000-4000-8000-000000000011', 'Harbour Piped Gas',        'GAS',         'BP number',         '^[0-9]{10}$'),
    ('b1000000-0000-4000-8000-000000000012', 'Meshnet Broadband',        'BROADBAND',   'Customer ID',       '^[0-9]{9}$');

-- A fetched bill: one per (user, biller, consumer number, billing month). The amount is simulated but stable.
CREATE TABLE bills (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID          NOT NULL REFERENCES users (id),
    biller_id             UUID          NOT NULL REFERENCES billers (id),
    consumer_number       VARCHAR(20)   NOT NULL,
    billing_month         CHAR(7)       NOT NULL,
    amount                NUMERIC(19,4) NOT NULL,
    due_date              DATE          NOT NULL,
    customer_name_masked  VARCHAR(100)  NOT NULL,
    expires_at            TIMESTAMPTZ   NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT bills_amount_chk CHECK (amount > 0)
);
CREATE UNIQUE INDEX bills_cycle_uq ON bills (user_id, biller_id, consumer_number, billing_month);

-- A bill can be paid once (unique bill_id), always through the ledger (journal_entry_id).
CREATE TABLE bill_payments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL REFERENCES users (id),
    bill_id           UUID          NOT NULL REFERENCES bills (id),
    biller_id         UUID          NOT NULL REFERENCES billers (id),
    from_account_id   UUID          NOT NULL REFERENCES accounts (id),
    consumer_number   VARCHAR(20)   NOT NULL,
    amount            NUMERIC(19,4) NOT NULL,
    journal_entry_id  UUID          NOT NULL REFERENCES journal_entries (id),
    transaction_id    UUID          NOT NULL REFERENCES ledger_lines (id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT bill_payments_amount_chk CHECK (amount > 0)
);
CREATE UNIQUE INDEX bill_payments_bill_uq ON bill_payments (bill_id);
CREATE INDEX bill_payments_user_idx ON bill_payments (user_id, created_at DESC);
