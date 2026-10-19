-- V2-M6 risk, rules-based core (PRD 11.4.7, 12.8; ARCHITECTURE 12.11; D-030, D-031).

-- A scored transfer can wait for review (money held) and then be released (COMPLETED) or rejected.
ALTER TABLE transfers DROP CONSTRAINT transfers_status_chk;
ALTER TABLE transfers ADD CONSTRAINT transfers_status_chk
    CHECK (status IN ('COMPLETED', 'FAILED', 'PENDING_REVIEW', 'REJECTED'));
ALTER TABLE transfers ADD CONSTRAINT transfers_posted_chk
    CHECK ((status = 'COMPLETED') = (journal_entry_id IS NOT NULL) OR status = 'FAILED');

-- One case per held transfer. signals is the JSON array the rules produced; memo is written later by the Fraud
-- Investigator agent (V2-M4) and is advisory only.
CREATE TABLE fraud_cases (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    transfer_id      UUID         NOT NULL REFERENCES transfers (id),
    user_id          UUID         NOT NULL REFERENCES users (id),
    hold_id          UUID         NOT NULL REFERENCES holds (id),
    score            INT          NOT NULL,
    signals          TEXT         NOT NULL,
    status           VARCHAR(10)  NOT NULL DEFAULT 'OPEN',
    memo             TEXT,
    decided_by       UUID REFERENCES users (id),
    decided_at       TIMESTAMPTZ,
    decision_reason  VARCHAR(500),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fraud_cases_status_chk CHECK (status IN ('OPEN', 'RELEASED', 'REJECTED')),
    CONSTRAINT fraud_cases_score_chk CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT fraud_cases_decided_chk CHECK ((status = 'OPEN') = (decided_at IS NULL))
);
CREATE UNIQUE INDEX fraud_cases_transfer_uq ON fraud_cases (transfer_id);
CREATE INDEX fraud_cases_status_idx ON fraud_cases (status, created_at DESC);

-- Fast velocity / round-amount lookups for the rules.
CREATE INDEX transfers_user_external_idx ON transfers (user_id, created_at DESC) WHERE internal = FALSE;

-- Loan documents (F-RISK-4): bytes live on disk under a random name, never in the DB.
CREATE TABLE loan_documents (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    loan_id       UUID         NOT NULL REFERENCES loans (id),
    user_id       UUID         NOT NULL REFERENCES users (id),
    file_name     VARCHAR(80)  NOT NULL,
    content_type  VARCHAR(40)  NOT NULL,
    size_bytes    BIGINT       NOT NULL,
    sha256        CHAR(64)     NOT NULL,
    storage_key   VARCHAR(80)  NOT NULL,
    consent_at    TIMESTAMPTZ  NOT NULL,
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT loan_documents_type_chk CHECK (content_type IN ('application/pdf', 'image/png', 'image/jpeg')),
    CONSTRAINT loan_documents_size_chk CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);
CREATE INDEX loan_documents_loan_idx ON loan_documents (loan_id, uploaded_at);
CREATE UNIQUE INDEX loan_documents_storage_uq ON loan_documents (storage_key);

-- Written by the Underwriting Analyst agent (V2-M4); the admin still decides (D-031).
CREATE TABLE underwriting_memos (
    loan_id         UUID PRIMARY KEY REFERENCES loans (id),
    status          VARCHAR(8)   NOT NULL DEFAULT 'PENDING',
    memo            TEXT,
    extracted       TEXT         NOT NULL DEFAULT '[]',
    flags           TEXT         NOT NULL DEFAULT '[]',
    recommendation  VARCHAR(8),
    generated_at    TIMESTAMPTZ,
    CONSTRAINT underwriting_status_chk CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT underwriting_rec_chk CHECK (recommendation IS NULL OR recommendation IN ('APPROVE', 'REJECT', 'REVIEW'))
);
