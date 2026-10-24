-- V2-M4 copilot (PRD 12.6; D-045: agent-quant is stateless, the core keeps conversations and traces).
-- Conversations and their runs are deleted after 30 days (F-AGT-7) or when the user deletes them.
CREATE TABLE copilot_conversations (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL REFERENCES users (id),
    title       VARCHAR(80)  NOT NULL DEFAULT 'New conversation',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX copilot_conversations_user_idx ON copilot_conversations (user_id, updated_at DESC);

-- One model "turn": the Concierge and the specialists it called. Token counts feed the budgets (D-039).
CREATE TABLE agent_runs (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          UUID          NOT NULL REFERENCES users (id),
    conversation_id  UUID          REFERENCES copilot_conversations (id) ON DELETE CASCADE,
    kind             VARCHAR(16)   NOT NULL,
    status           VARCHAR(10)   NOT NULL DEFAULT 'RUNNING',
    error_code       VARCHAR(30),
    input_tokens     BIGINT        NOT NULL DEFAULT 0,
    output_tokens    BIGINT        NOT NULL DEFAULT 0,
    cost_usd         NUMERIC(19,6) NOT NULL DEFAULT 0,
    started_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    finished_at      TIMESTAMPTZ,
    CONSTRAINT agent_runs_kind_chk CHECK (kind IN ('CHAT', 'COMPILE')),
    CONSTRAINT agent_runs_status_chk CHECK (status IN ('RUNNING', 'OK', 'ERROR'))
);
CREATE INDEX agent_runs_user_idx ON agent_runs (user_id, started_at);
CREATE INDEX agent_runs_started_idx ON agent_runs (started_at);

-- Each model call and tool call of a run, as reported (already redacted) by agent-quant.
CREATE TABLE agent_steps (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id         UUID          NOT NULL REFERENCES agent_runs (id) ON DELETE CASCADE,
    seq            INT           NOT NULL,
    agent          VARCHAR(24)   NOT NULL,
    kind           VARCHAR(8)    NOT NULL,
    name           VARCHAR(60)   NOT NULL,
    status         VARCHAR(8)    NOT NULL,
    latency_ms     INT           NOT NULL DEFAULT 0,
    input_tokens   INT           NOT NULL DEFAULT 0,
    output_tokens  INT           NOT NULL DEFAULT 0,
    detail         VARCHAR(500),
    CONSTRAINT agent_steps_kind_chk CHECK (kind IN ('MODEL', 'TOOL')),
    CONSTRAINT agent_steps_status_chk CHECK (status IN ('OK', 'ERROR')),
    CONSTRAINT agent_steps_seq_uq UNIQUE (run_id, seq)
);

CREATE TABLE copilot_messages (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id  UUID          NOT NULL REFERENCES copilot_conversations (id) ON DELETE CASCADE,
    role             VARCHAR(10)   NOT NULL,
    text             VARCHAR(8000) NOT NULL,
    intent_ids       VARCHAR(400)  NOT NULL DEFAULT '',
    citations        TEXT          NOT NULL DEFAULT '[]',
    run_id           UUID          REFERENCES agent_runs (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT copilot_messages_role_chk CHECK (role IN ('USER', 'ASSISTANT'))
);
CREATE INDEX copilot_messages_conversation_idx ON copilot_messages (conversation_id, created_at);
