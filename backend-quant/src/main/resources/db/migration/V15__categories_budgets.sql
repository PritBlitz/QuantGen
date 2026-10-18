-- V2-M3 Insights (PRD 11.4.4 F-INS-1/2, 12.5, 12.14): spending categories, recategorization, monthly budgets.

-- The fixed category list (PRD 12.14). Keys are the API values; labels are for display.
CREATE TABLE categories (
    key         VARCHAR(16) PRIMARY KEY,
    label       VARCHAR(40) NOT NULL,
    sort_order  SMALLINT    NOT NULL
);
INSERT INTO categories (key, label, sort_order) VALUES
    ('FOOD',          'Food and dining',  1),
    ('GROCERIES',     'Groceries',        2),
    ('SHOPPING',      'Shopping',         3),
    ('TRANSPORT',     'Transport',        4),
    ('BILLS',         'Bills and utilities', 5),
    ('RENT',          'Rent',             6),
    ('ENTERTAINMENT', 'Entertainment',    7),
    ('HEALTH',        'Health',           8),
    ('EDUCATION',     'Education',        9),
    ('TRAVEL',        'Travel',           10),
    ('TRANSFERS',     'Transfers',        11),
    ('INVESTMENTS',   'Investments',      12),
    ('LOANS',         'Loans',            13),
    ('INCOME',        'Income',           14),
    ('OTHER',         'Other',            15);

-- "Always use this category for payments to this account" (applyToCounterparty).
CREATE TABLE category_overrides (
    user_id                  UUID        NOT NULL REFERENCES users (id),
    counterparty_account_id  UUID        NOT NULL REFERENCES accounts (id),
    category                 VARCHAR(16) NOT NULL REFERENCES categories (key),
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, counterparty_account_id)
);

-- A category the owner picked for one transaction (one ledger line of their account).
CREATE TABLE transaction_categories (
    line_id     UUID        PRIMARY KEY REFERENCES ledger_lines (id),
    user_id     UUID        NOT NULL REFERENCES users (id),
    category    VARCHAR(16) NOT NULL REFERENCES categories (key),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE budgets (
    user_id        UUID          NOT NULL REFERENCES users (id),
    category       VARCHAR(16)   NOT NULL REFERENCES categories (key),
    monthly_limit  NUMERIC(19,4) NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, category),
    CONSTRAINT budgets_limit_chk CHECK (monthly_limit > 0)
);

-- Each budget alert (80% / 100%) goes out at most once per category per month.
CREATE TABLE budget_alerts (
    user_id    UUID        NOT NULL REFERENCES users (id),
    category   VARCHAR(16) NOT NULL,
    month      CHAR(7)     NOT NULL,
    threshold  SMALLINT    NOT NULL,
    sent_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, category, month, threshold),
    CONSTRAINT budget_alerts_threshold_chk CHECK (threshold IN (80, 100))
);
