-- F-RISK-6 reconcile explainer: one advisory explanation per mismatch state (account, cached and computed balance),
-- queued when /admin/ledger/reconcile finds it and written by a worker through agent-quant under the ai flag.
CREATE TABLE reconcile_explanations (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    ledger_account_id  UUID          NOT NULL REFERENCES ledger_accounts (id),
    cached_balance     NUMERIC(19,4) NOT NULL,
    computed_balance   NUMERIC(19,4) NOT NULL,
    status             VARCHAR(8)    NOT NULL DEFAULT 'PENDING',
    attempts           INT           NOT NULL DEFAULT 0,
    claimed_at         TIMESTAMPTZ,
    summary            TEXT,
    likely_cause       VARCHAR(200),
    affected_entries   TEXT          NOT NULL DEFAULT '[]',
    run_id             UUID          REFERENCES agent_runs (id),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT reconcile_explanations_status_chk CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    CONSTRAINT reconcile_explanations_state_uq UNIQUE (ledger_account_id, cached_balance, computed_balance)
);

ALTER TABLE agent_runs DROP CONSTRAINT agent_runs_kind_chk;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_kind_chk
    CHECK (kind IN ('CHAT', 'COMPILE', 'FRAUD_MEMO', 'UNDERWRITING', 'RECONCILE'));
