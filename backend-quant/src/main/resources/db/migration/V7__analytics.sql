-- V2-MA analytics (PRD 11.4.14, D-040): the two things analytics cannot derive from existing tables.

-- Failed money requests (F-ANL-1 failed-request rate, F-ANL-3 top error codes, F-ANL-8 error rate).
-- Written by the error handler for money endpoints only. No request bodies, amounts or account numbers: just which
-- endpoint failed with which code. user_id lets the admin breakdown suppress cells that cover fewer than 5 users.
CREATE TABLE request_failures (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    endpoint    VARCHAR(60) NOT NULL,
    code        VARCHAR(40) NOT NULL,
    status      SMALLINT    NOT NULL,
    user_id     UUID REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX request_failures_created_idx ON request_failures (created_at);

-- Nightly portfolio snapshots (F-ANL-15): price ticks are pruned after 7 days, so history has to be written down.
CREATE TABLE portfolio_snapshots (
    user_id       UUID          NOT NULL REFERENCES users (id),
    day           DATE          NOT NULL,
    market_value  NUMERIC(19,4) NOT NULL,
    invested      NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (user_id, day)
);
