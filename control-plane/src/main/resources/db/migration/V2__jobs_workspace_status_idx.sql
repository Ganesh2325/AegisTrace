-- Queue depth counts open jobs for one workspace. The claim index is
-- (status, next_run_at) and cannot seek by workspace_id.
CREATE INDEX jobs_workspace_open_idx ON jobs (workspace_id)
    WHERE status IN ('PENDING', 'RETRY', 'RUNNING');
