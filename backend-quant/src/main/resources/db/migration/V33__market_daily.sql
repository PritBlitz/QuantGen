-- D-047: real NSE end-of-day closes fetched daily for the 14 real companies (after the vendored file, which ends on
-- 2026-01-22), corporate-action factors that back-adjust older closes, and the sync's own state (including the
-- one-time switch of live prices to real levels).
CREATE TABLE market_daily_closes (
    symbol      VARCHAR(10)   NOT NULL REFERENCES instruments (symbol),
    day         DATE          NOT NULL,
    open_price  NUMERIC(19,4) NOT NULL,
    high        NUMERIC(19,4) NOT NULL,
    low         NUMERIC(19,4) NOT NULL,
    close       NUMERIC(19,4) NOT NULL,
    prev_close  NUMERIC(19,4) NOT NULL,
    volume      BIGINT        NOT NULL,
    fetched_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, day),
    CONSTRAINT market_daily_closes_ohlc_chk CHECK (low > 0 AND low <= LEAST(open_price, close)
        AND GREATEST(open_price, close) <= high AND prev_close > 0 AND volume >= 0)
);

-- Closes dated before effective_date are multiplied by factor (a 1:1 bonus is 0.5).
CREATE TABLE market_adjustments (
    symbol          VARCHAR(10)    NOT NULL REFERENCES instruments (symbol),
    effective_date  DATE           NOT NULL,
    factor          NUMERIC(20,10) NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    PRIMARY KEY (symbol, effective_date),
    CONSTRAINT market_adjustments_factor_chk CHECK (factor > 0)
);

CREATE TABLE market_sync_state (
    id              SMALLINT    PRIMARY KEY DEFAULT 1,
    -- Every calendar day up to here has been checked (a holiday has no file and no rows).
    synced_through  DATE,
    -- A run claims the sync by setting this; a claim older than 30 minutes is treated as dead.
    running_since   TIMESTAMPTZ,
    -- When live prices were first moved to real levels (open orders on those symbols were cancelled then).
    rebased_at      TIMESTAMPTZ,
    CONSTRAINT market_sync_state_single_chk CHECK (id = 1)
);
INSERT INTO market_sync_state (id) VALUES (1);
