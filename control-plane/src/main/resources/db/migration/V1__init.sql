CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    email           TEXT NOT NULL UNIQUE,
    password_hash   TEXT NOT NULL,
    display_name    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workspaces (
    id          UUID PRIMARY KEY,
    name        TEXT NOT NULL,
    slug        TEXT NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE memberships (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES workspaces(id),
    user_id       UUID NOT NULL REFERENCES users(id),
    role          TEXT NOT NULL CHECK (role IN ('DEVELOPER', 'OPERATOR', 'REVIEWER', 'ADMIN')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, user_id)
);

CREATE TABLE workspace_settings (
    workspace_id             UUID PRIMARY KEY REFERENCES workspaces(id),
    max_runs_per_minute      INT NOT NULL DEFAULT 30 CHECK (max_runs_per_minute > 0),
    approval_ttl_seconds     INT NOT NULL DEFAULT 900 CHECK (approval_ttl_seconds > 0),
    question_retention_days  INT NOT NULL DEFAULT 180 CHECK (question_retention_days > 0),
    content_logging          BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE TABLE login_failures (
    id          BIGSERIAL PRIMARY KEY,
    email       TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX login_failures_email_created_idx ON login_failures (email, created_at DESC);

CREATE TABLE knowledge_bases (
    id                UUID PRIMARY KEY,
    workspace_id      UUID NOT NULL REFERENCES workspaces(id),
    name              TEXT NOT NULL,
    slug              TEXT NOT NULL,
    embedding_model   TEXT NOT NULL,
    embedding_dim     INT NOT NULL DEFAULT 384,
    status            TEXT NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DISABLED')),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, slug)
);

CREATE TABLE agents (
    id            UUID PRIMARY KEY,
    workspace_id  UUID NOT NULL REFERENCES workspaces(id),
    name          TEXT NOT NULL,
    description   TEXT NOT NULL DEFAULT '',
    status        TEXT NOT NULL DEFAULT 'INACTIVE' CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_by    UUID NOT NULL REFERENCES users(id),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE prompt_versions (
    id              UUID PRIMARY KEY,
    agent_id        UUID NOT NULL REFERENCES agents(id),
    version_number  INT NOT NULL CHECK (version_number > 0),
    system_prompt   TEXT NOT NULL,
    created_by      UUID NOT NULL REFERENCES users(id),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, version_number)
);

CREATE TABLE agent_versions (
    id                  UUID PRIMARY KEY,
    agent_id            UUID NOT NULL REFERENCES agents(id),
    version_number      INT NOT NULL CHECK (version_number > 0),
    provider            TEXT NOT NULL,
    model               TEXT NOT NULL,
    temperature         DOUBLE PRECISION NOT NULL,
    max_tokens          INT NOT NULL CHECK (max_tokens > 0),
    timeout_ms          INT NOT NULL CHECK (timeout_ms > 0),
    max_tool_calls      INT NOT NULL CHECK (max_tool_calls > 0),
    cost_budget_usd     NUMERIC(12, 6) NOT NULL CHECK (cost_budget_usd >= 0),
    prompt_version_id   UUID NOT NULL REFERENCES prompt_versions(id),
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id),
    environment         TEXT NOT NULL,
    current_version     BOOLEAN NOT NULL DEFAULT FALSE,
    snapshot            JSONB NOT NULL,
    created_by          UUID NOT NULL REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (agent_id, version_number)
);
CREATE UNIQUE INDEX agent_versions_one_current_idx
    ON agent_versions (agent_id) WHERE current_version;

CREATE TABLE tools (
    id                      UUID PRIMARY KEY,
    name                    TEXT NOT NULL UNIQUE,
    description             TEXT NOT NULL,
    input_schema            JSONB NOT NULL,
    output_schema           JSONB NOT NULL,
    classification          TEXT NOT NULL CHECK (classification IN ('READ_ONLY', 'WRITE')),
    required_permission     TEXT NOT NULL,
    risk_level              TEXT NOT NULL CHECK (risk_level IN ('LOW', 'MEDIUM', 'HIGH')),
    approval_required       BOOLEAN NOT NULL,
    timeout_ms              INT NOT NULL,
    idempotency_required    BOOLEAN NOT NULL
);

CREATE TABLE agent_version_tools (
    agent_version_id  UUID NOT NULL REFERENCES agent_versions(id),
    tool_id           UUID NOT NULL REFERENCES tools(id),
    PRIMARY KEY (agent_version_id, tool_id)
);

CREATE TABLE documents (
    id                  UUID PRIMARY KEY,
    workspace_id        UUID NOT NULL REFERENCES workspaces(id),
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id),
    title               TEXT NOT NULL,
    media_type          TEXT NOT NULL,
    storage_key         TEXT NOT NULL,
    checksum_sha256     TEXT NOT NULL,
    status              TEXT NOT NULL CHECK (status IN ('UPLOADED', 'PROCESSING', 'ACTIVE', 'FAILED', 'DISABLED')),
    error_message       TEXT,
    created_by          UUID REFERENCES users(id),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX documents_kb_status_idx ON documents (knowledge_base_id, status);

CREATE TABLE document_chunks (
    id              UUID PRIMARY KEY,
    document_id     UUID NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    chunk_index     INT NOT NULL,
    section         TEXT NOT NULL DEFAULT '',
    page_number     INT,
    content         TEXT NOT NULL,
    embedding       vector(384) NOT NULL,
    embedding_model TEXT NOT NULL,
    content_tsv     tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    UNIQUE (document_id, chunk_index)
);
CREATE INDEX document_chunks_embedding_idx
    ON document_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX document_chunks_tsv_idx ON document_chunks USING gin (content_tsv);
CREATE INDEX document_chunks_document_idx ON document_chunks (document_id);

CREATE TABLE agent_runs (
    id                  UUID PRIMARY KEY,
    workspace_id        UUID NOT NULL REFERENCES workspaces(id),
    user_id             UUID NOT NULL REFERENCES users(id),
    agent_id            UUID NOT NULL REFERENCES agents(id),
    agent_version_id    UUID NOT NULL REFERENCES agent_versions(id),
    prompt_version_id   UUID NOT NULL REFERENCES prompt_versions(id),
    knowledge_base_id   UUID NOT NULL REFERENCES knowledge_bases(id),
    request_id          TEXT NOT NULL,
    trace_id            TEXT NOT NULL,
    state               TEXT NOT NULL,
    failure_category    TEXT,
    error_code          TEXT,
    error_message       TEXT,
    question            TEXT NOT NULL,
    draft_answer        TEXT,
    final_response      TEXT,
    citations           JSONB NOT NULL DEFAULT '[]'::jsonb,
    input_tokens        INT NOT NULL DEFAULT 0,
    output_tokens       INT NOT NULL DEFAULT 0,
    estimated_cost_usd  NUMERIC(12, 6) NOT NULL DEFAULT 0,
    provider            TEXT NOT NULL,
    model               TEXT NOT NULL,
    snapshot            JSONB NOT NULL,
    event_seq           INT NOT NULL DEFAULT 0,
    orchestration_attempts INT NOT NULL DEFAULT 0,
    started_at          TIMESTAMPTZ,
    ended_at            TIMESTAMPTZ,
    timeout_at          TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (state IN (
        'QUEUED', 'RUNNING', 'RETRIEVING', 'THINKING', 'TOOL_PROPOSED',
        'APPROVAL_REQUIRED', 'APPROVED', 'REJECTED', 'TOOL_EXECUTING',
        'COMPLETED', 'FAILED', 'CANCELLED', 'TIMED_OUT'
    ))
);
CREATE INDEX agent_runs_workspace_created_idx ON agent_runs (workspace_id, created_at DESC);
CREATE INDEX agent_runs_user_created_idx ON agent_runs (user_id, created_at DESC);
CREATE INDEX agent_runs_state_timeout_idx ON agent_runs (state, timeout_at);

CREATE TABLE run_events (
    id          UUID PRIMARY KEY,
    run_id      UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    sequence    INT NOT NULL,
    event_type  TEXT NOT NULL,
    state       TEXT NOT NULL,
    payload     JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, sequence)
);

CREATE TABLE tool_proposals (
    id                UUID PRIMARY KEY,
    run_id            UUID NOT NULL REFERENCES agent_runs(id),
    sequence          INT NOT NULL,
    tool_name         TEXT NOT NULL,
    arguments         JSONB NOT NULL,
    reason            TEXT NOT NULL,
    risk_level        TEXT NOT NULL,
    policy_decision   TEXT,
    policy_code       TEXT,
    policy_reason     TEXT,
    required_role     TEXT,
    idempotency_key   TEXT NOT NULL UNIQUE,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, sequence)
);

CREATE TABLE approvals (
    id               UUID PRIMARY KEY,
    workspace_id     UUID NOT NULL REFERENCES workspaces(id),
    run_id           UUID NOT NULL REFERENCES agent_runs(id),
    proposal_id      UUID NOT NULL UNIQUE REFERENCES tool_proposals(id),
    requester_id     UUID NOT NULL REFERENCES users(id),
    reviewer_id      UUID REFERENCES users(id),
    status           TEXT NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED', 'CANCELLED')),
    required_role    TEXT NOT NULL,
    decision_reason  TEXT,
    requested_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ,
    expires_at       TIMESTAMPTZ NOT NULL
);
CREATE INDEX approvals_queue_idx ON approvals (workspace_id, status, requested_at DESC);

CREATE TABLE jobs (
    id                UUID PRIMARY KEY,
    workspace_id      UUID REFERENCES workspaces(id),
    job_type          TEXT NOT NULL,
    payload           JSONB NOT NULL,
    status            TEXT NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'RETRY', 'SUCCEEDED', 'DEAD')),
    attempts          INT NOT NULL DEFAULT 0,
    max_attempts      INT NOT NULL DEFAULT 5,
    next_run_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error        TEXT,
    idempotency_key   TEXT NOT NULL UNIQUE,
    locked_by         TEXT,
    locked_until      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX jobs_claim_idx ON jobs (status, next_run_at);

CREATE TABLE job_attempts (
    id            UUID PRIMARY KEY,
    job_id        UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt       INT NOT NULL,
    error_type    TEXT,
    error_message TEXT,
    retryable     BOOLEAN NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE tool_executions (
    id                UUID PRIMARY KEY,
    workspace_id      UUID NOT NULL REFERENCES workspaces(id),
    run_id            UUID NOT NULL REFERENCES agent_runs(id),
    proposal_id       UUID NOT NULL UNIQUE REFERENCES tool_proposals(id),
    idempotency_key   TEXT NOT NULL UNIQUE,
    tool_name         TEXT NOT NULL,
    status            TEXT NOT NULL CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'SKIPPED')),
    result            JSONB,
    error_type        TEXT,
    error_message     TEXT,
    started_at        TIMESTAMPTZ,
    ended_at          TIMESTAMPTZ
);

CREATE TABLE tickets (
    id                UUID PRIMARY KEY,
    workspace_id      UUID NOT NULL REFERENCES workspaces(id),
    run_id            UUID NOT NULL REFERENCES agent_runs(id),
    proposal_id       UUID NOT NULL REFERENCES tool_proposals(id),
    idempotency_key   TEXT NOT NULL UNIQUE,
    subject           TEXT NOT NULL,
    description       TEXT NOT NULL,
    priority          TEXT NOT NULL,
    category          TEXT NOT NULL,
    status            TEXT NOT NULL DEFAULT 'OPEN',
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX tickets_run_idx ON tickets (run_id);

CREATE TABLE evaluations (
    id                   UUID PRIMARY KEY,
    workspace_id         UUID NOT NULL REFERENCES workspaces(id),
    run_id               UUID NOT NULL REFERENCES agent_runs(id),
    agent_version_id     UUID NOT NULL,
    prompt_version_id    UUID NOT NULL,
    model                TEXT NOT NULL,
    dataset_version      TEXT NOT NULL,
    evaluator_version    TEXT NOT NULL,
    scores               JSONB NOT NULL,
    passed               BOOLEAN NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX evaluations_run_evaluator_idx ON evaluations (run_id, evaluator_version);
CREATE INDEX evaluations_run_idx ON evaluations (run_id, created_at DESC);

CREATE TABLE audit_events (
    id             UUID PRIMARY KEY,
    workspace_id   UUID,
    actor_id       UUID,
    action         TEXT NOT NULL,
    resource_type  TEXT NOT NULL,
    resource_id    TEXT,
    trace_id       TEXT,
    run_id         UUID,
    request_id     TEXT,
    metadata       JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX audit_events_workspace_created_idx ON audit_events (workspace_id, created_at DESC);
CREATE INDEX audit_events_action_idx ON audit_events (action, created_at DESC);
