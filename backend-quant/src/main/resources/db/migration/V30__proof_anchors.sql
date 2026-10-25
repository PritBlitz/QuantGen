-- F-POB-6 external anchoring: the git commit that published a day's root to a public repository (the owner pushes).
-- Insert-only, like the snapshots it points at.
CREATE TABLE proof_anchors (
    snapshot_date  DATE        PRIMARY KEY REFERENCES balance_snapshots (snapshot_date),
    commit_sha     CHAR(40)    NOT NULL,
    committed_at   TIMESTAMPTZ NOT NULL,
    CONSTRAINT proof_anchors_sha_chk CHECK (commit_sha ~ '^[0-9a-f]{40}$')
);
CREATE TRIGGER proof_anchors_immutable
    BEFORE UPDATE OR DELETE ON proof_anchors
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER proof_anchors_no_truncate
    BEFORE TRUNCATE ON proof_anchors
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();
