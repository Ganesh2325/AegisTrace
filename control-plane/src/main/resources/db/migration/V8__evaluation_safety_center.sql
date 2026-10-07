CREATE INDEX IF NOT EXISTS evaluations_workspace_created_idx
    ON evaluations (workspace_id, created_at DESC);

ALTER TABLE evaluations
    ADD CONSTRAINT evaluations_agent_version_fk
        FOREIGN KEY (agent_version_id) REFERENCES agent_versions(id) NOT VALID,
    ADD CONSTRAINT evaluations_prompt_version_fk
        FOREIGN KEY (prompt_version_id) REFERENCES prompt_versions(id) NOT VALID;

ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS retrieved_evidence JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS safety_evidence JSONB NOT NULL DEFAULT '[]'::jsonb;

CREATE TABLE evaluation_cases (
    id                  UUID PRIMARY KEY,
    workspace_id        UUID NOT NULL REFERENCES workspaces(id),
    case_key            TEXT NOT NULL,
    version_number      INT NOT NULL CHECK (version_number > 0),
    name                TEXT NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    category            TEXT NOT NULL CHECK (category IN (
                            'CORRECTNESS', 'GROUNDING', 'CITATION', 'POLICY',
                            'TOOL_BEHAVIOR', 'ABSTENTION', 'SAFETY', 'PROMPT_INJECTION'
                        )),
    execution_type      TEXT NOT NULL CHECK (execution_type IN ('RUN', 'POLICY')),
    input_text          TEXT NOT NULL,
    expectations        JSONB NOT NULL,
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    fixture_source      TEXT,
    created_by          UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, case_key, version_number)
);
CREATE INDEX evaluation_cases_workspace_category_idx
    ON evaluation_cases (workspace_id, category, created_at DESC);

CREATE TABLE evaluation_suites (
    id                  UUID PRIMARY KEY,
    workspace_id        UUID NOT NULL REFERENCES workspaces(id),
    suite_key           TEXT NOT NULL,
    version_number      INT NOT NULL CHECK (version_number > 0),
    name                TEXT NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    fixture_source      TEXT,
    created_by          UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, suite_key, version_number)
);
CREATE INDEX evaluation_suites_workspace_created_idx
    ON evaluation_suites (workspace_id, created_at DESC);

CREATE TABLE evaluation_suite_cases (
    suite_id       UUID NOT NULL REFERENCES evaluation_suites(id),
    case_id        UUID NOT NULL REFERENCES evaluation_cases(id),
    position       INT NOT NULL CHECK (position >= 0),
    PRIMARY KEY (suite_id, case_id),
    UNIQUE (suite_id, position)
);

CREATE TABLE evaluation_executions (
    id                          UUID PRIMARY KEY,
    workspace_id                UUID NOT NULL REFERENCES workspaces(id),
    suite_id                    UUID NOT NULL REFERENCES evaluation_suites(id),
    requested_by                UUID NOT NULL REFERENCES users(id),
    agent_version_id            UUID NOT NULL REFERENCES agent_versions(id),
    knowledge_base_version_id   UUID NOT NULL REFERENCES knowledge_base_versions(id),
    provider                    TEXT NOT NULL,
    model                       TEXT NOT NULL,
    evaluator_version           TEXT NOT NULL,
    status                      TEXT NOT NULL CHECK (status IN (
                                    'QUEUED', 'RUNNING', 'COMPLETED', 'PARTIAL',
                                    'ERROR', 'CANCELLED'
                                )),
    request_key                 TEXT NOT NULL,
    trace_id                    TEXT,
    started_at                  TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, request_key)
);
CREATE INDEX evaluation_executions_workspace_created_idx
    ON evaluation_executions (workspace_id, created_at DESC);
CREATE INDEX evaluation_executions_workspace_status_idx
    ON evaluation_executions (workspace_id, status, created_at DESC);

CREATE TABLE evaluation_results (
    id                  UUID PRIMARY KEY,
    execution_id        UUID NOT NULL REFERENCES evaluation_executions(id),
    case_id             UUID NOT NULL REFERENCES evaluation_cases(id),
    product_run_id      UUID REFERENCES agent_runs(id),
    status              TEXT NOT NULL CHECK (status IN (
                            'QUEUED', 'RUNNING', 'PASS', 'FAIL', 'ERROR',
                            'SKIPPED', 'INCONCLUSIVE', 'CANCELLED'
                        )),
    score               NUMERIC(8, 6),
    failure_category    TEXT,
    explanation         TEXT,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (execution_id, case_id),
    UNIQUE (product_run_id),
    CHECK (score IS NULL OR (score >= 0 AND score <= 1))
);
CREATE INDEX evaluation_results_execution_status_idx
    ON evaluation_results (execution_id, status);

CREATE TABLE evaluation_checks (
    id                  UUID PRIMARY KEY,
    result_id           UUID NOT NULL REFERENCES evaluation_results(id) ON DELETE CASCADE,
    check_key           TEXT NOT NULL,
    status              TEXT NOT NULL CHECK (status IN ('PASS', 'FAIL', 'ERROR', 'SKIPPED', 'INCONCLUSIVE')),
    score               NUMERIC(8, 6),
    failure_category    TEXT,
    explanation         TEXT NOT NULL,
    evidence            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (result_id, check_key),
    CHECK (score IS NULL OR (score >= 0 AND score <= 1))
);

CREATE TABLE safety_signals (
    id                  UUID PRIMARY KEY,
    workspace_id        UUID NOT NULL REFERENCES workspaces(id),
    run_id              UUID REFERENCES agent_runs(id),
    evaluation_result_id UUID REFERENCES evaluation_results(id),
    signal_type         TEXT NOT NULL,
    disposition         TEXT NOT NULL CHECK (disposition IN (
                            'DETECTED', 'BLOCKED', 'APPROVED', 'REJECTED',
                            'ABSTAINED', 'FAILED', 'UNKNOWN'
                        )),
    severity            TEXT NOT NULL CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    evidence            JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX safety_signals_workspace_created_idx
    ON safety_signals (workspace_id, created_at DESC);
CREATE INDEX safety_signals_workspace_disposition_idx
    ON safety_signals (workspace_id, disposition, created_at DESC);

CREATE OR REPLACE FUNCTION evaluation_definition_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'evaluation definitions and evidence are immutable';
END;
$$;

CREATE TRIGGER evaluation_cases_no_change
    BEFORE UPDATE OR DELETE ON evaluation_cases
    FOR EACH ROW EXECUTE FUNCTION evaluation_definition_immutable();
CREATE TRIGGER evaluation_suites_no_change
    BEFORE UPDATE OR DELETE ON evaluation_suites
    FOR EACH ROW EXECUTE FUNCTION evaluation_definition_immutable();
CREATE TRIGGER evaluation_suite_cases_no_change
    BEFORE UPDATE OR DELETE ON evaluation_suite_cases
    FOR EACH ROW EXECUTE FUNCTION evaluation_definition_immutable();
CREATE TRIGGER evaluation_checks_no_change
    BEFORE UPDATE OR DELETE ON evaluation_checks
    FOR EACH ROW EXECUTE FUNCTION evaluation_definition_immutable();
CREATE TRIGGER safety_signals_no_change
    BEFORE UPDATE OR DELETE ON safety_signals
    FOR EACH ROW EXECUTE FUNCTION evaluation_definition_immutable();
CREATE TRIGGER legacy_evaluations_no_change
    BEFORE UPDATE OR DELETE ON evaluations
    FOR EACH ROW EXECUTE FUNCTION evaluation_definition_immutable();

CREATE OR REPLACE FUNCTION evaluation_terminal_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'evaluation executions and results cannot be deleted';
    END IF;
    IF TG_TABLE_NAME = 'evaluation_results'
       AND OLD.status IN ('PASS', 'FAIL', 'ERROR', 'SKIPPED', 'INCONCLUSIVE', 'CANCELLED') THEN
        RAISE EXCEPTION 'completed evaluation results are immutable';
    END IF;
    IF TG_TABLE_NAME = 'evaluation_executions'
       AND OLD.status IN ('COMPLETED', 'PARTIAL', 'ERROR', 'CANCELLED') THEN
        RAISE EXCEPTION 'completed evaluation executions are immutable';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER evaluation_results_terminal_no_change
    BEFORE UPDATE OR DELETE ON evaluation_results
    FOR EACH ROW EXECUTE FUNCTION evaluation_terminal_immutable();
CREATE TRIGGER evaluation_executions_terminal_no_change
    BEFORE UPDATE OR DELETE ON evaluation_executions
    FOR EACH ROW EXECUTE FUNCTION evaluation_terminal_immutable();
