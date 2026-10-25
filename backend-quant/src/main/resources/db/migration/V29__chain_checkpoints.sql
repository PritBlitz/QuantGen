-- F-POB-4 checkpoints: after a verification finds the seal chain intact up to its head, that position and hash are
-- recorded. A checkpointed verification re-checks that seal and recomputes only the chain after it; a full one
-- (the admin default, and the Sunday nightly run) still starts from genesis. Insert-only, like the ledger.
CREATE TABLE ledger_chain_checkpoints (
    position     BIGINT      PRIMARY KEY,
    hash         CHAR(64)    NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER ledger_chain_checkpoints_immutable
    BEFORE UPDATE OR DELETE ON ledger_chain_checkpoints
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER ledger_chain_checkpoints_no_truncate
    BEFORE TRUNCATE ON ledger_chain_checkpoints
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();
