-- V2-M2 Payments 2.0, part 2 (PRD 11.4.3 F-PAY-9, 12.4): scheduled and recurring transfers.

-- One instruction. The destination account is fixed when the instruction is created (what the customer confirmed),
-- even if it was chosen by handle and the handle is later pointed elsewhere.
CREATE TABLE scheduled_transfers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID          NOT NULL REFERENCES users (id),
    from_account_id     UUID          NOT NULL REFERENCES accounts (id),
    to_account_id       UUID          NOT NULL REFERENCES accounts (id),
    to_account_number   CHAR(12)      NOT NULL,
    to_name_masked      VARCHAR(100)  NOT NULL,
    amount              NUMERIC(19,4) NOT NULL,
    note                VARCHAR(140),
    frequency           VARCHAR(8)    NOT NULL,
    start_date          DATE          NOT NULL,
    end_date            DATE,
    remaining_count     INT,
    -- The occurrence being worked on (null once COMPLETED or CANCELLED).
    next_run_date       DATE,
    -- Set when that occurrence failed once: the day of its single retry.
    retry_on            DATE,
    status              VARCHAR(10)   NOT NULL DEFAULT 'ACTIVE',
    last_run_date       DATE,
    last_outcome        VARCHAR(10),
    last_transfer_id    UUID REFERENCES transfers (id),
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT scheduled_transfers_amount_chk CHECK (amount > 0),
    CONSTRAINT scheduled_transfers_distinct_chk CHECK (from_account_id <> to_account_id),
    CONSTRAINT scheduled_transfers_frequency_chk CHECK (frequency IN ('ONCE', 'WEEKLY', 'MONTHLY')),
    CONSTRAINT scheduled_transfers_status_chk CHECK (status IN ('ACTIVE', 'PAUSED', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT scheduled_transfers_outcome_chk CHECK (last_outcome IS NULL
        OR last_outcome IN ('SUCCEEDED', 'RETRYING', 'MISSED')),
    CONSTRAINT scheduled_transfers_count_chk CHECK (remaining_count IS NULL OR remaining_count >= 0),
    CONSTRAINT scheduled_transfers_next_chk CHECK ((status IN ('ACTIVE', 'PAUSED')) = (next_run_date IS NOT NULL))
);
CREATE INDEX scheduled_transfers_user_idx ON scheduled_transfers (user_id, created_at DESC);
CREATE INDEX scheduled_transfers_due_idx ON scheduled_transfers (next_run_date) WHERE status = 'ACTIVE';

-- Every attempt. (instruction, occurrence, attempt) is unique, so a second job run can't pay an occurrence twice;
-- the transfer itself also goes through the idempotency table with a key derived from the same triple.
CREATE TABLE scheduled_transfer_runs (
    id                     UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheduled_transfer_id  UUID        NOT NULL REFERENCES scheduled_transfers (id),
    occurrence_date        DATE        NOT NULL,
    attempt                SMALLINT    NOT NULL,
    run_date               DATE        NOT NULL,
    outcome                VARCHAR(10) NOT NULL,
    transfer_id            UUID REFERENCES transfers (id),
    error_code             VARCHAR(40),
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT scheduled_transfer_runs_attempt_chk CHECK (attempt IN (1, 2)),
    CONSTRAINT scheduled_transfer_runs_outcome_chk CHECK (outcome IN ('SUCCEEDED', 'RETRYING', 'MISSED')),
    CONSTRAINT scheduled_transfer_runs_uq UNIQUE (scheduled_transfer_id, occurrence_date, attempt)
);
