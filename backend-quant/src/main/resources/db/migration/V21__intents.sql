-- V2-M4 core side: intents (PRD 11.4.5 F-AGT-2, 12.6; D-022, D-023). An agent (or an MCP client with a
-- read+propose token) proposes; nothing happens until the user approves in the app.
CREATE TABLE intents (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID          NOT NULL REFERENCES users (id),
    kind              VARCHAR(20)   NOT NULL,
    payload           TEXT          NOT NULL,
    summary           TEXT          NOT NULL,
    rationale         VARCHAR(1000) NOT NULL,
    risk_level        VARCHAR(6)    NOT NULL,
    requires_step_up  BOOLEAN       NOT NULL,
    proposed_by       VARCHAR(20)   NOT NULL,
    conversation_id   UUID,
    source_token_id   UUID REFERENCES personal_access_tokens (id),
    status            VARCHAR(10)   NOT NULL DEFAULT 'PROPOSED',
    failure_code      VARCHAR(40),
    result_type       VARCHAR(20),
    result_id         UUID,
    expires_at        TIMESTAMPTZ   NOT NULL,
    decided_at        TIMESTAMPTZ,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT intents_kind_chk CHECK (kind IN ('TRANSFER', 'BILL_PAYMENT', 'SCHEDULED_TRANSFER', 'PAYMENT_REQUEST',
        'ORDER', 'GOAL_TRANSFER', 'FD_BOOKING', 'LOAN_APPLICATION', 'AUTOPILOT_RULE')),
    CONSTRAINT intents_status_chk CHECK (status IN ('PROPOSED', 'EXECUTED', 'REJECTED', 'EXPIRED', 'FAILED')),
    CONSTRAINT intents_risk_chk CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT intents_failed_chk CHECK ((status = 'FAILED') = (failure_code IS NOT NULL)),
    CONSTRAINT intents_result_chk CHECK ((status = 'EXECUTED') = (result_id IS NOT NULL))
);
CREATE INDEX intents_user_idx ON intents (user_id, created_at DESC);
CREATE INDEX intents_open_idx ON intents (expires_at) WHERE status = 'PROPOSED';
