-- V2-M8 Proof of Balance (PRD 11.4.9, 12.10; ARCHITECTURE 12.10; D-025, D-026).

-- The hash chain over journal entries. Written only by LedgerSealer, after the entry commits.
-- hash = SHA256("<prev_hash>|<canonical(entry, lines)>"); position 1 chains from 64 zeros.
CREATE TABLE ledger_seals (
    position    BIGINT      PRIMARY KEY,
    entry_id    UUID        NOT NULL REFERENCES journal_entries (id),
    prev_hash   CHAR(64)    NOT NULL,
    hash        CHAR(64)    NOT NULL,
    sealed_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ledger_seals_position_chk CHECK (position > 0)
);
-- An entry is sealed at most once.
CREATE UNIQUE INDEX ledger_seals_entry_uq ON ledger_seals (entry_id);
-- The sealer looks for unsealed entries by creation time.
CREATE INDEX journal_entries_created_idx ON journal_entries (created_at, id);

-- One published Merkle sum tree per IST date.
CREATE TABLE balance_snapshots (
    id                 UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_date      DATE          NOT NULL,
    root               CHAR(64)      NOT NULL,
    total_liabilities  NUMERIC(19,4) NOT NULL,
    leaf_count         INT           NOT NULL,
    chain_position     BIGINT        NOT NULL,
    chain_hash         CHAR(64)      NOT NULL,
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT balance_snapshots_liabilities_chk CHECK (total_liabilities >= 0),
    CONSTRAINT balance_snapshots_leaf_count_chk CHECK (leaf_count >= 0)
);
CREATE UNIQUE INDEX balance_snapshots_date_uq ON balance_snapshots (snapshot_date);

-- Every node of the tree: level 0 holds the leaves (account_id and salt set for real leaves, null for padding),
-- the single node at the top level is the root.
CREATE TABLE balance_snapshot_nodes (
    snapshot_id  UUID          NOT NULL REFERENCES balance_snapshots (id),
    level        SMALLINT      NOT NULL,
    idx          INT           NOT NULL,
    hash         CHAR(64)      NOT NULL,
    sum          NUMERIC(19,4) NOT NULL,
    account_id   UUID,
    salt         VARCHAR(64),
    PRIMARY KEY (snapshot_id, level, idx),
    CONSTRAINT balance_snapshot_nodes_sum_chk CHECK (sum >= 0),
    CONSTRAINT balance_snapshot_nodes_leaf_chk CHECK (account_id IS NULL OR level = 0),
    CONSTRAINT balance_snapshot_nodes_salt_chk CHECK ((account_id IS NULL) = (salt IS NULL))
);
CREATE UNIQUE INDEX balance_snapshot_nodes_account_uq ON balance_snapshot_nodes (snapshot_id, account_id)
    WHERE account_id IS NOT NULL;

-- All three are insert-only, like the ledger itself (ledger_reject_mutation is from V1).
CREATE TRIGGER ledger_seals_immutable
    BEFORE UPDATE OR DELETE ON ledger_seals
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER ledger_seals_no_truncate
    BEFORE TRUNCATE ON ledger_seals
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER balance_snapshots_immutable
    BEFORE UPDATE OR DELETE ON balance_snapshots
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER balance_snapshots_no_truncate
    BEFORE TRUNCATE ON balance_snapshots
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER balance_snapshot_nodes_immutable
    BEFORE UPDATE OR DELETE ON balance_snapshot_nodes
    FOR EACH ROW EXECUTE FUNCTION ledger_reject_mutation();
CREATE TRIGGER balance_snapshot_nodes_no_truncate
    BEFORE TRUNCATE ON balance_snapshot_nodes
    FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_mutation();
