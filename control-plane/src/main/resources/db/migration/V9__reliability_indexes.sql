CREATE UNIQUE INDEX IF NOT EXISTS documents_kb_live_checksum_uidx
    ON documents (knowledge_base_id, checksum_sha256)
    WHERE status IN ('UPLOADED', 'PROCESSING', 'ACTIVE');

CREATE INDEX IF NOT EXISTS jobs_running_lease_idx
    ON jobs (locked_until)
    WHERE status = 'RUNNING';

CREATE INDEX IF NOT EXISTS agent_runs_workspace_active_created_idx
    ON agent_runs (workspace_id, created_at DESC)
    WHERE state NOT IN ('COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT');

CREATE UNIQUE INDEX IF NOT EXISTS agent_runs_request_idempotency_uidx
    ON agent_runs (workspace_id, user_id, request_id);
