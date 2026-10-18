-- V2-M7 Trading 2.0 (PRD 11.4.8, 12.9, 12.14): limit and stop-loss orders, watchlists, price alerts, backtests.

-- An OPEN order shows its limit (or trigger) price and the estimated total until it fills; then the fill price.
ALTER TABLE orders ADD COLUMN order_type    VARCHAR(10) NOT NULL DEFAULT 'MARKET';
ALTER TABLE orders ADD COLUMN limit_price   NUMERIC(19,4);
ALTER TABLE orders ADD COLUMN trigger_price NUMERIC(19,4);
ALTER TABLE orders ADD COLUMN validity      VARCHAR(3);
ALTER TABLE orders ADD COLUMN expires_at    TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN filled_at     TIMESTAMPTZ;
ALTER TABLE orders ADD COLUMN closed_at     TIMESTAMPTZ;
-- The cash hold of an open BUY (released when it fills, is cancelled or expires).
ALTER TABLE orders ADD COLUMN hold_id       UUID REFERENCES holds (id);
UPDATE orders SET filled_at = created_at WHERE status = 'FILLED';
ALTER TABLE orders DROP CONSTRAINT orders_status_chk;
ALTER TABLE orders ADD CONSTRAINT orders_status_chk
    CHECK (status IN ('FILLED', 'REJECTED', 'OPEN', 'CANCELLED', 'EXPIRED'));
ALTER TABLE orders ADD CONSTRAINT orders_type_chk CHECK (order_type IN ('MARKET', 'LIMIT', 'STOP_LOSS'));
ALTER TABLE orders ADD CONSTRAINT orders_validity_chk CHECK (validity IS NULL OR validity IN ('DAY', 'GTC'));
ALTER TABLE orders ADD CONSTRAINT orders_prices_chk CHECK (
    (order_type = 'MARKET' AND limit_price IS NULL AND trigger_price IS NULL)
 OR (order_type = 'LIMIT' AND limit_price > 0 AND trigger_price IS NULL)
 OR (order_type = 'STOP_LOSS' AND side = 'SELL' AND trigger_price > 0 AND limit_price IS NULL));
ALTER TABLE orders ADD CONSTRAINT orders_open_chk CHECK (status <> 'OPEN' OR (expires_at IS NOT NULL AND validity IS NOT NULL));
ALTER TABLE orders ADD CONSTRAINT orders_filled_chk CHECK ((status = 'FILLED') = (filled_at IS NOT NULL));
CREATE INDEX orders_open_idx ON orders (symbol) WHERE status = 'OPEN';

-- Shares promised to open SELL orders (limit and stop) can't be sold twice.
ALTER TABLE holdings ADD COLUMN reserved_qty BIGINT NOT NULL DEFAULT 0;
ALTER TABLE holdings ADD CONSTRAINT holdings_reserved_chk CHECK (reserved_qty >= 0 AND reserved_qty <= quantity);

CREATE TABLE watchlists (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id),
    name        VARCHAR(40) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX watchlists_user_idx ON watchlists (user_id, created_at);

CREATE TABLE watchlist_symbols (
    watchlist_id  UUID        NOT NULL REFERENCES watchlists (id) ON DELETE CASCADE,
    symbol        VARCHAR(10) NOT NULL REFERENCES instruments (symbol),
    added_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (watchlist_id, symbol)
);

CREATE TABLE price_alerts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id       UUID          NOT NULL REFERENCES users (id),
    symbol        VARCHAR(10)   NOT NULL REFERENCES instruments (symbol),
    direction     VARCHAR(5)    NOT NULL,
    price         NUMERIC(19,4) NOT NULL,
    status        VARCHAR(9)    NOT NULL DEFAULT 'ACTIVE',
    triggered_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT price_alerts_direction_chk CHECK (direction IN ('ABOVE', 'BELOW')),
    CONSTRAINT price_alerts_price_chk CHECK (price > 0),
    CONSTRAINT price_alerts_status_chk CHECK (status IN ('ACTIVE', 'TRIGGERED')),
    CONSTRAINT price_alerts_triggered_chk CHECK ((status = 'TRIGGERED') = (triggered_at IS NOT NULL))
);
CREATE INDEX price_alerts_active_idx ON price_alerts (symbol) WHERE status = 'ACTIVE';
CREATE INDEX price_alerts_user_idx ON price_alerts (user_id, created_at DESC);

-- A finished backtest (the result is stored as JSON; it is only ever read back whole).
CREATE TABLE backtests (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID        NOT NULL REFERENCES users (id),
    result      JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX backtests_user_idx ON backtests (user_id, created_at DESC);
