-- F-RISK-3 Fraud Investigator memos (V2-M6 agent part). A worker claims PENDING cases, asks agent-quant for an
-- advisory memo from masked case facts, and stores it; after 3 failed attempts the memo is FAILED. The queue works
-- with or without a memo (D-030: rules decide, the model only explains).
ALTER TABLE fraud_cases ADD COLUMN memo_status VARCHAR(8) NOT NULL DEFAULT 'PENDING';
ALTER TABLE fraud_cases ADD COLUMN memo_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE fraud_cases ADD COLUMN memo_claimed_at TIMESTAMPTZ;
ALTER TABLE fraud_cases ADD CONSTRAINT fraud_cases_memo_status_chk
    CHECK (memo_status IN ('PENDING', 'RUNNING', 'READY', 'FAILED'));
UPDATE fraud_cases SET memo_status = 'READY' WHERE memo IS NOT NULL;
CREATE INDEX fraud_cases_memo_pending_idx ON fraud_cases (created_at) WHERE memo_status IN ('PENDING', 'RUNNING');

-- Back-office runs belong to no customer (and don't count against anyone's budget).
ALTER TABLE agent_runs ALTER COLUMN user_id DROP NOT NULL;
ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_kind_chk;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_kind_chk
    CHECK (kind IN ('CHAT', 'COMPILE', 'FRAUD_MEMO', 'UNDERWRITING'));
