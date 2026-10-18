-- V2-M3 Savings, part 1 (PRD 11.4.4 F-SAV-1/2, 12.5, D-037): goal accounts and round-ups.

ALTER TABLE accounts DROP CONSTRAINT accounts_type_chk;
ALTER TABLE accounts ADD CONSTRAINT accounts_type_chk
    CHECK (type IN ('SAVINGS', 'CHECKING', 'GOAL', 'FIXED_DEPOSIT'));

-- 1:1 with an account of type GOAL.
CREATE TABLE goals (
    account_id   UUID          PRIMARY KEY REFERENCES accounts (id),
    name         VARCHAR(40)   NOT NULL,
    target       NUMERIC(19,4) NOT NULL,
    target_date  DATE,
    emoji        VARCHAR(16),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT goals_target_chk CHECK (target > 0)
);

-- F-SAV-2: per everyday account, round spends up to the next 10/50/100 into one goal.
CREATE TABLE round_up_settings (
    account_id       UUID        PRIMARY KEY REFERENCES accounts (id),
    enabled          BOOLEAN     NOT NULL,
    round_to         SMALLINT    NOT NULL,
    goal_account_id  UUID REFERENCES accounts (id),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT round_up_settings_to_chk CHECK (round_to IN (10, 50, 100)),
    CONSTRAINT round_up_settings_goal_chk CHECK (NOT enabled OR goal_account_id IS NOT NULL)
);

-- One row per qualifying spend (unique per ledger entry and account); swept into the goal once a day as one
-- summed posting. SKIPPED when the sweep could not move it (not enough money, or an account can't post).
CREATE TABLE round_up_accruals (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_account_id  UUID          NOT NULL REFERENCES accounts (id),
    goal_account_id    UUID          NOT NULL REFERENCES accounts (id),
    journal_entry_id   UUID          NOT NULL REFERENCES journal_entries (id),
    spend_amount       NUMERIC(19,4) NOT NULL,
    amount             NUMERIC(19,4) NOT NULL,
    day                DATE          NOT NULL,
    status             VARCHAR(8)    NOT NULL DEFAULT 'PENDING',
    sweep_entry_id     UUID REFERENCES journal_entries (id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT round_up_accruals_amount_chk CHECK (amount > 0),
    CONSTRAINT round_up_accruals_status_chk CHECK (status IN ('PENDING', 'SWEPT', 'SKIPPED')),
    CONSTRAINT round_up_accruals_swept_chk CHECK ((status = 'SWEPT') = (sweep_entry_id IS NOT NULL))
);
CREATE UNIQUE INDEX round_up_accruals_entry_uq ON round_up_accruals (journal_entry_id, source_account_id);
CREATE INDEX round_up_accruals_pending_idx ON round_up_accruals (day) WHERE status = 'PENDING';
