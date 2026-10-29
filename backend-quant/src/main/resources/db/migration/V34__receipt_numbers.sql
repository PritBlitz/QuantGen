-- PRD §12.4 "Payments view": every payment gets a human receipt number, 'QG' + IST yymmdd + a global sequence of at
-- least 7 digits (QG2610300000123). One sequence across all payment tables, so a number is never reused anywhere.
CREATE SEQUENCE payment_receipt_seq;

CREATE FUNCTION receipt_no(ts TIMESTAMPTZ, n BIGINT) RETURNS VARCHAR AS $$
    SELECT 'QG' || to_char(ts AT TIME ZONE 'Asia/Kolkata', 'YYMMDD') || lpad(n::text, greatest(7, length(n::text)), '0')
$$ LANGUAGE sql STABLE;

ALTER TABLE transfers ADD COLUMN receipt_no VARCHAR(24);
ALTER TABLE bill_payments ADD COLUMN receipt_no VARCHAR(24);
ALTER TABLE topups ADD COLUMN receipt_no VARCHAR(24);
ALTER TABLE card_authorizations ADD COLUMN receipt_no VARCHAR(24);
ALTER TABLE card_refunds ADD COLUMN receipt_no VARCHAR(24);

-- Existing rows, numbered in one creation order across every table.
CREATE TEMP TABLE receipt_backfill ON COMMIT DROP AS
SELECT src, id, created_at, row_number() OVER (ORDER BY created_at, src, id) AS n FROM (
    SELECT 'T' AS src, id, created_at FROM transfers
    UNION ALL SELECT 'B', id, created_at FROM bill_payments
    UNION ALL SELECT 'P', id, created_at FROM topups
    UNION ALL SELECT 'C', id, created_at FROM card_authorizations
    UNION ALL SELECT 'R', id, created_at FROM card_refunds) x;
UPDATE transfers t SET receipt_no = receipt_no(b.created_at, b.n) FROM receipt_backfill b WHERE b.src = 'T' AND b.id = t.id;
UPDATE bill_payments t SET receipt_no = receipt_no(b.created_at, b.n) FROM receipt_backfill b WHERE b.src = 'B' AND b.id = t.id;
UPDATE topups t SET receipt_no = receipt_no(b.created_at, b.n) FROM receipt_backfill b WHERE b.src = 'P' AND b.id = t.id;
UPDATE card_authorizations t SET receipt_no = receipt_no(b.created_at, b.n) FROM receipt_backfill b WHERE b.src = 'C' AND b.id = t.id;
UPDATE card_refunds t SET receipt_no = receipt_no(b.created_at, b.n) FROM receipt_backfill b WHERE b.src = 'R' AND b.id = t.id;
SELECT setval('payment_receipt_seq', COALESCE((SELECT max(n) FROM receipt_backfill), 0) + 1, false);

-- New rows: numbered by the database on insert, so no code path can forget or reuse one.
ALTER TABLE transfers ALTER COLUMN receipt_no SET DEFAULT receipt_no(now(), nextval('payment_receipt_seq')), ALTER COLUMN receipt_no SET NOT NULL;
ALTER TABLE bill_payments ALTER COLUMN receipt_no SET DEFAULT receipt_no(now(), nextval('payment_receipt_seq')), ALTER COLUMN receipt_no SET NOT NULL;
ALTER TABLE topups ALTER COLUMN receipt_no SET DEFAULT receipt_no(now(), nextval('payment_receipt_seq')), ALTER COLUMN receipt_no SET NOT NULL;
ALTER TABLE card_authorizations ALTER COLUMN receipt_no SET DEFAULT receipt_no(now(), nextval('payment_receipt_seq')), ALTER COLUMN receipt_no SET NOT NULL;
ALTER TABLE card_refunds ALTER COLUMN receipt_no SET DEFAULT receipt_no(now(), nextval('payment_receipt_seq')), ALTER COLUMN receipt_no SET NOT NULL;

CREATE UNIQUE INDEX transfers_receipt_uq ON transfers (receipt_no);
CREATE UNIQUE INDEX bill_payments_receipt_uq ON bill_payments (receipt_no);
CREATE UNIQUE INDEX topups_receipt_uq ON topups (receipt_no);
CREATE UNIQUE INDEX card_authorizations_receipt_uq ON card_authorizations (receipt_no);
CREATE UNIQUE INDEX card_refunds_receipt_uq ON card_refunds (receipt_no);

-- The payments view lists by owner; transfers are also read from the receiving side.
CREATE INDEX IF NOT EXISTS transfers_to_account_idx ON transfers (to_account_id, created_at DESC);
CREATE INDEX IF NOT EXISTS topups_user_idx ON topups (user_id, created_at DESC);
