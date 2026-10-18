-- V2-M3 Savings, part 2 (PRD 11.4.4 F-SAV-3/4, 12.5): fixed deposits, savings interest, INTEREST_EXPENSE.

ALTER TABLE journal_entries DROP CONSTRAINT journal_entries_type_chk;
ALTER TABLE journal_entries ADD CONSTRAINT journal_entries_type_chk CHECK (type IN
    ('DEPOSIT', 'TRANSFER', 'TRADE', 'LOAN_DISBURSAL', 'LOAN_REPAYMENT', 'REVERSAL', 'BILL_PAYMENT', 'TOPUP',
     'INTEREST'));

-- Interest the bank pays out to customers (FDs and savings accounts).
INSERT INTO ledger_accounts (id, kind, code, normal_side) VALUES
    ('00000000-0000-0000-0000-000000000007', 'SYSTEM', 'INTEREST_EXPENSE', 'DEBIT');

-- The published FD rate slabs (simple interest, % p.a.). Tenures are whole days, both bounds inclusive.
CREATE TABLE fixed_deposit_rates (
    min_days     INTEGER      PRIMARY KEY,
    max_days     INTEGER      NOT NULL,
    annual_rate  NUMERIC(5,2) NOT NULL,
    CONSTRAINT fixed_deposit_rates_days_chk CHECK (min_days >= 1 AND max_days >= min_days),
    CONSTRAINT fixed_deposit_rates_rate_chk CHECK (annual_rate >= 0)
);
INSERT INTO fixed_deposit_rates (min_days, max_days, annual_rate) VALUES
    (7,    45,   3.50),
    (46,   90,   4.50),
    (91,   180,  5.50),
    (181,  364,  6.25),
    (365,  729,  7.00),
    (730,  1095, 7.25),
    (1096, 1825, 6.75);

-- One row per deposit term. The money sits in an account of type FIXED_DEPOSIT (account_id); a renewal starts a
-- new row on the same account. payout_account_id is the everyday account it was booked from and pays back into.
CREATE TABLE fixed_deposits (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id            UUID          NOT NULL REFERENCES users (id),
    account_id         UUID          NOT NULL REFERENCES accounts (id),
    payout_account_id  UUID          NOT NULL REFERENCES accounts (id),
    principal          NUMERIC(19,4) NOT NULL,
    annual_rate        NUMERIC(5,2)  NOT NULL,
    tenure_days        INTEGER       NOT NULL,
    start_date         DATE          NOT NULL,
    maturity_date      DATE          NOT NULL,
    on_maturity        VARCHAR(6)    NOT NULL,
    status             VARCHAR(8)    NOT NULL DEFAULT 'ACTIVE',
    interest_paid      NUMERIC(19,4),
    closed_at          TIMESTAMPTZ,
    renewed_from       UUID REFERENCES fixed_deposits (id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT fixed_deposits_principal_chk CHECK (principal > 0),
    CONSTRAINT fixed_deposits_tenure_chk CHECK (tenure_days BETWEEN 7 AND 1825),
    CONSTRAINT fixed_deposits_on_maturity_chk CHECK (on_maturity IN ('PAYOUT', 'RENEW')),
    CONSTRAINT fixed_deposits_status_chk CHECK (status IN ('ACTIVE', 'MATURED', 'CLOSED')),
    CONSTRAINT fixed_deposits_done_chk CHECK ((status = 'ACTIVE') = (interest_paid IS NULL))
);
-- At most one live term per FD account.
CREATE UNIQUE INDEX fixed_deposits_active_uq ON fixed_deposits (account_id) WHERE status = 'ACTIVE';
CREATE INDEX fixed_deposits_user_idx ON fixed_deposits (user_id, created_at DESC);
CREATE INDEX fixed_deposits_maturity_idx ON fixed_deposits (maturity_date) WHERE status = 'ACTIVE';

-- F-SAV-4: one row per SAVINGS account per day with a positive closing balance (4 dp), credited monthly.
CREATE TABLE interest_accruals (
    account_id       UUID          NOT NULL REFERENCES accounts (id),
    day              DATE          NOT NULL,
    closing_balance  NUMERIC(19,4) NOT NULL,
    annual_rate      NUMERIC(5,2)  NOT NULL,
    amount           NUMERIC(19,4) NOT NULL,
    credit_entry_id  UUID REFERENCES journal_entries (id),
    PRIMARY KEY (account_id, day),
    CONSTRAINT interest_accruals_amount_chk CHECK (amount >= 0)
);
CREATE INDEX interest_accruals_uncredited_idx ON interest_accruals (day) WHERE credit_entry_id IS NULL;
