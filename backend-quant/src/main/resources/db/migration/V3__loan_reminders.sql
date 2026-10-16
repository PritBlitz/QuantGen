-- EMI_DUE reminders are sent once per instalment by the daily loan job (F-NOT-1).
ALTER TABLE loan_installments ADD COLUMN reminder_sent_at TIMESTAMPTZ;

-- Admin loan queue: newest first, optionally filtered by status.
CREATE INDEX loans_created_idx ON loans (created_at DESC);
