CREATE INDEX IF NOT EXISTS approvals_run_idx ON approvals (run_id);
CREATE INDEX IF NOT EXISTS approvals_reviewer_idx ON approvals (reviewer_id);
CREATE INDEX IF NOT EXISTS approvals_pending_expires_idx ON approvals (expires_at) WHERE status = 'PENDING';
