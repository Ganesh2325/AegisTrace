CREATE INDEX IF NOT EXISTS audit_events_workspace_actor_created_idx
    ON audit_events (workspace_id, actor_id, created_at DESC);

CREATE INDEX IF NOT EXISTS audit_events_workspace_run_created_idx
    ON audit_events (workspace_id, run_id, created_at DESC)
    WHERE run_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS audit_events_workspace_trace_created_idx
    ON audit_events (workspace_id, trace_id, created_at DESC)
    WHERE trace_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS audit_events_workspace_resource_idx
    ON audit_events (workspace_id, resource_type, resource_id);

CREATE INDEX IF NOT EXISTS agent_runs_workspace_trace_idx
    ON agent_runs (workspace_id, trace_id);

CREATE OR REPLACE FUNCTION audit_events_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'audit_events are immutable';
END;
$$;

DROP TRIGGER IF EXISTS audit_events_no_update ON audit_events;
CREATE TRIGGER audit_events_no_update
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW
    EXECUTE FUNCTION audit_events_immutable();
