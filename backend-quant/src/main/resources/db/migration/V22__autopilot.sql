-- V2-M5 Autopilot core (PRD 11.4.6, 12.7; ARCHITECTURE 12.9; D-024): rules, versions, runs and run actions.
-- The engine is deterministic: no model is involved at run time.

-- An Autopilot transfer is marked so MONEY_RECEIVED rules can ignore it (no loops, F-AUTO-6). (V8 added 'AUTOPILOT'.)

CREATE TABLE autopilot_rules (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id               UUID         NOT NULL REFERENCES users (id),
    name                  VARCHAR(40)  NOT NULL,
    status                VARCHAR(8)   NOT NULL DEFAULT 'ACTIVE',
    version               INT          NOT NULL DEFAULT 1,
    trigger_type          VARCHAR(20)  NOT NULL,
    paused_reason         VARCHAR(200),
    consecutive_failures  INT          NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT autopilot_rules_status_chk CHECK (status IN ('ACTIVE', 'PAUSED', 'DELETED')),
    CONSTRAINT autopilot_rules_paused_chk CHECK ((status = 'PAUSED') = (paused_reason IS NOT NULL))
);
CREATE INDEX autopilot_rules_trigger_idx ON autopilot_rules (trigger_type, user_id) WHERE status = 'ACTIVE';
CREATE INDEX autopilot_rules_user_idx ON autopilot_rules (user_id, created_at);

-- Every edit is a new version; runs point at the version they ran.
CREATE TABLE autopilot_rule_versions (
    rule_id     UUID         NOT NULL REFERENCES autopilot_rules (id),
    version     INT          NOT NULL,
    dsl         TEXT         NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (rule_id, version)
);

-- One run per (rule, version, trigger event): a duplicate event is a no-op (F-AUTO-5).
CREATE TABLE autopilot_runs (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id           UUID          NOT NULL REFERENCES autopilot_rules (id),
    user_id           UUID          NOT NULL REFERENCES users (id),
    rule_version      INT           NOT NULL,
    trigger_event_id  VARCHAR(100)  NOT NULL,
    trigger_summary   VARCHAR(200)  NOT NULL,
    trigger_amount    NUMERIC(19,4),
    planned_total     NUMERIC(19,4) NOT NULL DEFAULT 0,
    outcome           VARCHAR(12),
    started_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at       TIMESTAMPTZ,
    CONSTRAINT autopilot_runs_outcome_chk CHECK (outcome IS NULL OR outcome IN ('SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED_CAP')),
    CONSTRAINT autopilot_runs_finished_chk CHECK ((outcome IS NULL) = (finished_at IS NULL))
);
CREATE UNIQUE INDEX autopilot_runs_event_uq ON autopilot_runs (rule_id, rule_version, trigger_event_id);
CREATE INDEX autopilot_runs_rule_idx ON autopilot_runs (rule_id, started_at DESC);
CREATE INDEX autopilot_runs_unfinished_idx ON autopilot_runs (started_at) WHERE finished_at IS NULL;

-- One row per action of a run, written in the same transaction as the action itself (or after it failed).
CREATE TABLE autopilot_run_actions (
    run_id       UUID          NOT NULL REFERENCES autopilot_runs (id),
    idx          SMALLINT      NOT NULL,
    type         VARCHAR(16)   NOT NULL,
    amount       NUMERIC(19,4),
    status       VARCHAR(10)   NOT NULL,
    transfer_id  UUID,
    order_id     UUID,
    error        VARCHAR(200),
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (run_id, idx),
    CONSTRAINT autopilot_run_actions_status_chk CHECK (status IN ('DONE', 'FAILED', 'SKIPPED'))
);
