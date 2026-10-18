-- V2-M2 Payments 2.0, part 4 (PRD 11.4.3 F-PAY-12, 12.4): split groups. No money moves here: settling up
-- creates payment requests, and a paid settle-up request counts as a settlement in the group's balances.

CREATE TABLE split_groups (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name        VARCHAR(40) NOT NULL,
    created_by  UUID        NOT NULL REFERENCES users (id),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE split_members (
    group_id   UUID        NOT NULL REFERENCES split_groups (id),
    user_id    UUID        NOT NULL REFERENCES users (id),
    position   SMALLINT    NOT NULL,
    joined_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (group_id, user_id)
);
CREATE INDEX split_members_user_idx ON split_members (user_id);

CREATE TABLE split_expenses (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id         UUID          NOT NULL REFERENCES split_groups (id),
    paid_by_user_id  UUID          NOT NULL REFERENCES users (id),
    amount           NUMERIC(19,4) NOT NULL,
    description      VARCHAR(60)   NOT NULL,
    split_type       VARCHAR(8)    NOT NULL,
    created_by       UUID          NOT NULL REFERENCES users (id),
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT split_expenses_amount_chk CHECK (amount > 0),
    CONSTRAINT split_expenses_type_chk CHECK (split_type IN ('EQUAL', 'EXACT', 'PERCENT'))
);
CREATE INDEX split_expenses_group_idx ON split_expenses (group_id, created_at DESC);

-- Each member's computed share of an expense; the shares of an expense always add up to its amount.
CREATE TABLE split_shares (
    expense_id  UUID          NOT NULL REFERENCES split_expenses (id),
    user_id     UUID          NOT NULL REFERENCES users (id),
    amount      NUMERIC(19,4) NOT NULL,
    PRIMARY KEY (expense_id, user_id),
    CONSTRAINT split_shares_amount_chk CHECK (amount >= 0)
);

-- Settle-up requests remember their group, so paying one settles the debt inside the group.
ALTER TABLE payment_requests ADD COLUMN split_group_id UUID REFERENCES split_groups (id);
CREATE INDEX payment_requests_split_idx ON payment_requests (split_group_id) WHERE split_group_id IS NOT NULL;
