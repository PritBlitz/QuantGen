-- QuantGen core schema (v1).
-- Conventions: UUID primary keys, timestamptz (UTC), money NUMERIC(19,4), quantities BIGINT.
-- See ARCHITECTURE.md §4 (ledger) and §8 (data model). Never edit this file once applied;
-- add V2__... instead.

-- ---------------------------------------------------------------------------
-- Users and sessions
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name             VARCHAR(100) NOT NULL,
    email                 VARCHAR(254) NOT NULL,
    phone                 VARCHAR(20)  NOT NULL,
    password_hash         VARCHAR(100) NOT NULL,
    role                  VARCHAR(16)  NOT NULL,
    failed_login_count    INT          NOT NULL DEFAULT 0,
    failed_window_start   TIMESTAMPTZ,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version               BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT users_role_chk CHECK (role IN ('CUSTOMER', 'ADMIN')),
    CONSTRAINT users_email_lower_chk CHECK (email = lower(email))
);
CREATE UNIQUE INDEX users_email_uq ON users (email);

CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID        NOT NULL REFERENCES users (id),
    family_id    UUID        NOT NULL,
    token_hash   CHAR(64)    NOT NULL,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    replaced_by  UUID,
    created_ip   VARCHAR(45),
    user_agent   VARCHAR(255),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX refresh_tokens_hash_uq ON refresh_tokens (token_hash);
CREATE INDEX refresh_tokens_family_idx ON refresh_tokens (family_id);
CREATE INDEX refresh_tokens_user_idx ON refresh_tokens (user_id);

-- ---------------------------------------------------------------------------
-- Ledger
-- ---------------------------------------------------------------------------
CREATE TABLE ledger_accounts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    kind         VARCHAR(16)   NOT NULL,
    code         VARCHAR(32),
    normal_side  VARCHAR(6)    NOT NULL,
    balance      NUMERIC(19,4) NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version      BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT ledger_accounts_kind_chk CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
    CONSTRAINT ledger_accounts_side_chk CHECK (normal_side IN ('DEBIT', 'CREDIT')),
    -- System accounts are identified by code; customer accounts have none.
    CONSTRAINT ledger_accounts_code_chk CHECK ((kind = 'SYSTEM') = (code IS NOT NULL)),
    -- Invariant 3: a customer balance is never negative (defence in depth for LedgerService).
    CONSTRAINT ledger_accounts_customer_nonneg_chk CHECK (kind <> 'CUSTOMER' OR balance >= 0)
);
CREATE UNIQUE INDEX ledger_accounts_code_uq ON ledger_accounts (code);

CREATE TABLE journal_entries (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    type               VARCHAR(32)  NOT NULL,
    description        VARCHAR(255) NOT NULL,
    reverses_entry_id  UUID REFERENCES journal_entries (id),
    created_by         UUID REFERENCES users (id),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT journal_entries_type_chk CHECK (type IN
        ('DEPOSIT', 'TRANSFER', 'TRADE', 'LOAN_DISBURSAL', 'LOAN_REPAYMENT', 'REVERSAL')),
    CONSTRAINT journal_entries_reversal_chk CHECK ((type = 'REVERSAL') = (reverses_entry_id IS NOT NULL))
);
-- An entry can be reversed at most once.
CREATE UNIQUE INDEX journal_entries_reverses_uq ON journal_entries (reverses_entry_id);

CREATE TABLE ledger_lines (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    journal_entry_id   UUID          NOT NULL REFERENCES journal_entries (id),
    ledger_account_id  UUID          NOT NULL REFERENCES ledger_accounts (id),
    direction          VARCHAR(6)    NOT NULL,
    amount             NUMERIC(19,4) NOT NULL,
    balance_after      NUMERIC(19,4) NOT NULL,
    counterparty       VARCHAR(120),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ledger_lines_direction_chk CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_lines_amount_chk CHECK (amount > 0)
);
CREATE INDEX ledger_lines_account_created_idx ON ledger_lines (ledger_account_id, created_at DESC);
CREATE INDEX ledger_lines_entry_idx ON ledger_lines (journal_entry_id);

-- Guard: journal entries and ledger lines are insert-only (invariant 5).
CREATE FUNCTION ledger_reject_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger table % is insert-only (% rejected)', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER journal_entries_no_truncate
    BEFORE TRUNCATE ON journal_entries
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER ledger_lines_immutable
    BEFORE UPDATE OR DELETE ON ledger_lines
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER ledger_lines_no_truncate
    BEFORE TRUNCATE ON ledger_lines
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();

-- Guard: at commit, every journal entry has >= 2 lines and sum(debits) = sum(credits) (invariant 1).
CREATE FUNCTION ledger_check_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    v_entry_id UUID;
    v_lines    BIGINT;
    v_debits   NUMERIC;
    v_credits  NUMERIC;
BEGIN
    IF TG_TABLE_NAME = 'journal_entries' THEN
        v_entry_id := NEW.id;
    ELSE
        v_entry_id := NEW.journal_entry_id;
    END IF;

    SELECT count(*),
           coalesce(sum(amount) FILTER (WHERE direction = 'DEBIT'), 0),
           coalesce(sum(amount) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO v_lines, v_debits, v_credits
      FROM ledger_lines
     WHERE journal_entry_id = v_entry_id;

    IF v_lines < 2 OR v_debits <> v_credits THEN
        RAISE EXCEPTION 'journal entry % is unbalanced (lines=%, debits=%, credits=%)',
            v_entry_id, v_lines, v_debits, v_credits
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_entries_balanced
    AFTER INSERT ON journal_entries
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_balanced();
CREATE CONSTRAINT TRIGGER ledger_lines_balanced
    AFTER INSERT ON ledger_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_entry_balanced();

-- System ledger accounts (fixed ids so code and tests can refer to them).
INSERT INTO ledger_accounts (id, kind, code, normal_side) VALUES
    ('00000000-0000-0000-0000-000000000001', 'SYSTEM', 'BANK_CASH',          'DEBIT'),
    ('00000000-0000-0000-0000-000000000002', 'SYSTEM', 'LOAN_RECEIVABLE',    'DEBIT'),
    ('00000000-0000-0000-0000-000000000003', 'SYSTEM', 'INTEREST_INCOME',    'CREDIT'),
    ('00000000-0000-0000-0000-000000000004', 'SYSTEM', 'BROKERAGE_CLEARING', 'CREDIT');

-- ---------------------------------------------------------------------------
-- Accounts (a customer account IS a ledger account: same id)
-- ---------------------------------------------------------------------------
CREATE TABLE accounts (
    id              UUID PRIMARY KEY REFERENCES ledger_accounts (id),
    user_id         UUID        NOT NULL REFERENCES users (id),
    account_number  CHAR(12)    NOT NULL,
    type            VARCHAR(16) NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    currency        CHAR(3)     NOT NULL DEFAULT 'INR',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    version         BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT accounts_type_chk CHECK (type IN ('SAVINGS', 'CHECKING')),
    CONSTRAINT accounts_status_chk CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    CONSTRAINT accounts_currency_chk CHECK (currency = 'INR'),
    CONSTRAINT accounts_number_chk CHECK (account_number ~ '^[0-9]{12}$')
);
CREATE UNIQUE INDEX accounts_number_uq ON accounts (account_number);
CREATE INDEX accounts_user_idx ON accounts (user_id);

-- ---------------------------------------------------------------------------
-- Idempotency
-- ---------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID        NOT NULL REFERENCES users (id),
    idem_key         UUID        NOT NULL,
    request_hash     CHAR(64)    NOT NULL,
    response_status  INT,
    response_body    TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idempotency_keys_user_key_uq ON idempotency_keys (user_id, idem_key);

-- ---------------------------------------------------------------------------
-- Payments
-- ---------------------------------------------------------------------------
CREATE TABLE transfers (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              UUID          NOT NULL REFERENCES users (id),
    from_account_id      UUID          NOT NULL REFERENCES accounts (id),
    to_account_id        UUID          NOT NULL REFERENCES accounts (id),
    from_account_number  CHAR(12)      NOT NULL,
    to_account_number    CHAR(12)      NOT NULL,
    to_name              VARCHAR(100)  NOT NULL,
    amount               NUMERIC(19,4) NOT NULL,
    note                 VARCHAR(140),
    status               VARCHAR(16)   NOT NULL,
    journal_entry_id     UUID REFERENCES journal_entries (id),
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT transfers_amount_chk CHECK (amount > 0),
    CONSTRAINT transfers_status_chk CHECK (status IN ('COMPLETED', 'FAILED')),
    CONSTRAINT transfers_distinct_chk CHECK (from_account_id <> to_account_id)
);
CREATE INDEX transfers_user_created_idx ON transfers (user_id, created_at DESC);
CREATE INDEX transfers_from_created_idx ON transfers (from_account_id, created_at DESC);

CREATE TABLE beneficiaries (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID         NOT NULL REFERENCES users (id),
    account_id      UUID         NOT NULL REFERENCES accounts (id),
    nickname        VARCHAR(50)  NOT NULL,
    account_number  CHAR(12)     NOT NULL,
    holder_name     VARCHAR(100) NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX beneficiaries_user_account_uq ON beneficiaries (user_id, account_number);

-- ---------------------------------------------------------------------------
-- Trading
-- ---------------------------------------------------------------------------
CREATE TABLE instruments (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    symbol      VARCHAR(10)   NOT NULL,
    name        VARCHAR(100)  NOT NULL,
    sector      VARCHAR(50)   NOT NULL,
    price       NUMERIC(19,4) NOT NULL,
    open_price  NUMERIC(19,4) NOT NULL,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version     BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT instruments_price_chk CHECK (price > 1)
);
CREATE UNIQUE INDEX instruments_symbol_uq ON instruments (symbol);

-- High-volume time series: a bigint identity key instead of a UUID.
CREATE TABLE price_ticks (
    id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    symbol  VARCHAR(10)   NOT NULL REFERENCES instruments (symbol),
    price   NUMERIC(19,4) NOT NULL,
    ts      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX price_ticks_symbol_ts_idx ON price_ticks (symbol, ts DESC);

CREATE TABLE orders (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL REFERENCES users (id),
    account_id        UUID          NOT NULL REFERENCES accounts (id),
    symbol            VARCHAR(10)   NOT NULL REFERENCES instruments (symbol),
    side              VARCHAR(4)    NOT NULL,
    quantity          BIGINT        NOT NULL,
    price             NUMERIC(19,4) NOT NULL,
    total             NUMERIC(19,4) NOT NULL,
    realized_pnl      NUMERIC(19,4),
    status            VARCHAR(16)   NOT NULL,
    journal_entry_id  UUID REFERENCES journal_entries (id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT orders_side_chk CHECK (side IN ('BUY', 'SELL')),
    CONSTRAINT orders_quantity_chk CHECK (quantity BETWEEN 1 AND 10000),
    CONSTRAINT orders_status_chk CHECK (status IN ('FILLED', 'REJECTED'))
);
CREATE INDEX orders_user_created_idx ON orders (user_id, created_at DESC);
CREATE INDEX orders_created_idx ON orders (created_at DESC);

-- cost_basis is the exact remaining cost of the position; avgCost = cost_basis / quantity.
CREATE TABLE holdings (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID          NOT NULL REFERENCES users (id),
    symbol        VARCHAR(10)   NOT NULL REFERENCES instruments (symbol),
    quantity      BIGINT        NOT NULL DEFAULT 0,
    cost_basis    NUMERIC(19,4) NOT NULL DEFAULT 0,
    realized_pnl  NUMERIC(19,4) NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version       BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT holdings_quantity_chk CHECK (quantity >= 0),
    CONSTRAINT holdings_cost_chk CHECK (cost_basis >= 0)
);
CREATE UNIQUE INDEX holdings_user_symbol_uq ON holdings (user_id, symbol);

-- ---------------------------------------------------------------------------
-- Loans
-- ---------------------------------------------------------------------------
CREATE TABLE loans (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                UUID          NOT NULL REFERENCES users (id),
    disburse_account_id    UUID          NOT NULL REFERENCES accounts (id),
    amount                 NUMERIC(19,4) NOT NULL,
    tenure_months          INT           NOT NULL,
    annual_rate            NUMERIC(7,4)  NOT NULL,
    emi                    NUMERIC(19,4) NOT NULL,
    monthly_income         NUMERIC(19,4) NOT NULL,
    purpose                VARCHAR(200)  NOT NULL,
    status                 VARCHAR(16)   NOT NULL,
    outstanding_principal  NUMERIC(19,4) NOT NULL,
    decision_reason        VARCHAR(500),
    decided_by             UUID REFERENCES users (id),
    decided_at             TIMESTAMPTZ,
    disbursal_entry_id     UUID REFERENCES journal_entries (id),
    disbursed_at           TIMESTAMPTZ,
    closed_at              TIMESTAMPTZ,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version                BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT loans_amount_chk CHECK (amount BETWEEN 10000 AND 1000000),
    CONSTRAINT loans_tenure_chk CHECK (tenure_months BETWEEN 6 AND 60),
    CONSTRAINT loans_status_chk CHECK (status IN ('PENDING', 'APPROVED', 'ACTIVE', 'CLOSED', 'REJECTED')),
    CONSTRAINT loans_outstanding_chk CHECK (outstanding_principal >= 0)
);
CREATE INDEX loans_user_idx ON loans (user_id, created_at DESC);
CREATE INDEX loans_status_idx ON loans (status, created_at);

CREATE TABLE loan_installments (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id         UUID          NOT NULL REFERENCES loans (id),
    installment_no  INT           NOT NULL,
    due_date        DATE          NOT NULL,
    emi             NUMERIC(19,4) NOT NULL,
    principal       NUMERIC(19,4) NOT NULL,
    interest        NUMERIC(19,4) NOT NULL,
    principal_paid  NUMERIC(19,4) NOT NULL DEFAULT 0,
    interest_paid   NUMERIC(19,4) NOT NULL DEFAULT 0,
    status          VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    paid_at         TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT loan_installments_status_chk CHECK (status IN ('PENDING', 'PAID', 'OVERDUE')),
    CONSTRAINT loan_installments_paid_chk CHECK (principal_paid <= principal AND interest_paid <= interest)
);
CREATE UNIQUE INDEX loan_installments_loan_no_uq ON loan_installments (loan_id, installment_no);
CREATE INDEX loan_installments_status_due_idx ON loan_installments (status, due_date);

CREATE TABLE loan_repayments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id           UUID          NOT NULL REFERENCES loans (id),
    account_id        UUID          NOT NULL REFERENCES accounts (id),
    amount            NUMERIC(19,4) NOT NULL,
    principal_paid    NUMERIC(19,4) NOT NULL,
    interest_paid     NUMERIC(19,4) NOT NULL,
    source            VARCHAR(16)   NOT NULL,
    journal_entry_id  UUID          NOT NULL REFERENCES journal_entries (id),
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT loan_repayments_amount_chk CHECK (amount > 0 AND amount = principal_paid + interest_paid),
    CONSTRAINT loan_repayments_source_chk CHECK (source IN ('MANUAL', 'AUTO_DEBIT'))
);
CREATE INDEX loan_repayments_loan_idx ON loan_repayments (loan_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- Notifications and audit
-- ---------------------------------------------------------------------------
CREATE TABLE notifications (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users (id),
    type        VARCHAR(40)  NOT NULL,
    title       VARCHAR(120) NOT NULL,
    body        VARCHAR(500) NOT NULL,
    read_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX notifications_user_created_idx ON notifications (user_id, created_at DESC);
CREATE INDEX notifications_user_unread_idx ON notifications (user_id) WHERE read_at IS NULL;

CREATE TABLE audit_logs (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_user_id  UUID REFERENCES users (id),
    actor_email    VARCHAR(254),
    action         VARCHAR(60)  NOT NULL,
    target_type    VARCHAR(40),
    target_id      VARCHAR(64),
    details        VARCHAR(1000),
    ip             VARCHAR(45),
    user_agent     VARCHAR(255),
    request_id     VARCHAR(64),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX audit_logs_created_idx ON audit_logs (created_at DESC);
CREATE INDEX audit_logs_actor_idx ON audit_logs (actor_user_id, created_at DESC);
CREATE INDEX audit_logs_action_idx ON audit_logs (action, created_at DESC);

-- ---------------------------------------------------------------------------
-- Seed: simulated instruments (F-TRD-1)
-- ---------------------------------------------------------------------------
INSERT INTO instruments (symbol, name, sector, price, open_price) VALUES
    ('QGEN', 'QuantGen Holdings',         'Financials',          842.50,  842.50),
    ('INFY', 'Infosys',                   'Information Tech',   1523.40, 1523.40),
    ('TCS',  'Tata Consultancy Services', 'Information Tech',   3912.75, 3912.75),
    ('RELI', 'Reliance Industries',       'Energy',             2876.10, 2876.10),
    ('HDFC', 'HDFC Bank',                 'Financials',         1648.90, 1648.90),
    ('ICIC', 'ICICI Bank',                'Financials',         1187.35, 1187.35),
    ('WIPR', 'Wipro',                     'Information Tech',    482.60,  482.60),
    ('ITCL', 'ITC',                       'Consumer Staples',    431.25,  431.25),
    ('LTIM', 'Larsen & Toubro',           'Industrials',        3540.00, 3540.00),
    ('BHAR', 'Bharti Airtel',             'Telecom',            1412.80, 1412.80),
    ('ASPN', 'Asian Paints',              'Materials',          2915.45, 2915.45),
    ('MARU', 'Maruti Suzuki',             'Automobiles',       12450.00, 12450.00),
    ('SUNP', 'Sun Pharma',                'Healthcare',         1689.20, 1689.20),
    ('TATM', 'Tata Motors',               'Automobiles',         948.70,  948.70),
    ('ADGR', 'Adani Green Energy',        'Utilities',          1765.30, 1765.30);
