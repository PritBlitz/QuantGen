-- Transfers between a customer's own accounts don't count towards the daily outgoing limit (F-PAY-3).
ALTER TABLE transfers ADD COLUMN internal BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX transfers_user_outgoing_idx ON transfers (user_id, created_at) WHERE NOT internal AND status = 'COMPLETED';
