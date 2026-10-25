-- F-RISK-5 Underwriting Analyst (V2-M6 agent part). Each upload (re)queues the loan's memo: requested_at moves on,
-- attempts reset. A worker claims memos whose last upload is a little while ago (so three uploads make one run),
-- reads the PDFs' text, masks it and asks agent-quant. Status stays PENDING while it works (the contract's values);
-- three failed attempts make it FAILED.
ALTER TABLE underwriting_memos ADD COLUMN requested_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE underwriting_memos ADD COLUMN attempts INT NOT NULL DEFAULT 0;
ALTER TABLE underwriting_memos ADD COLUMN claimed_at TIMESTAMPTZ;
ALTER TABLE underwriting_memos ADD COLUMN run_id UUID REFERENCES agent_runs (id);
CREATE INDEX underwriting_memos_pending_idx ON underwriting_memos (requested_at) WHERE status = 'PENDING';

-- Documents uploaded before this migration: queue their memo.
INSERT INTO underwriting_memos (loan_id, requested_at)
SELECT d.loan_id, max(d.uploaded_at) FROM loan_documents d JOIN loans l ON l.id = d.loan_id
 WHERE l.status = 'PENDING' GROUP BY d.loan_id
ON CONFLICT (loan_id) DO NOTHING;
