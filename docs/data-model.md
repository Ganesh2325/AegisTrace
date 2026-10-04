# Data Model

PostgreSQL is the system of record. Flyway migration `V1__init.sql` is the schema. Embeddings are `vector(384)`. A knowledge base is pinned to one embedding model so vectors are comparable.

## Identity and access

- `users` — email, bcrypt password hash, status.
- `workspaces` — tenant boundary. Every product row that is user data carries `workspace_id`.
- `memberships` — one role per user per workspace: `DEVELOPER`, `OPERATOR`, `REVIEWER`, `ADMIN`.
- `workspace_settings` — rate limit, approval ttl, content-logging flag.

## Agent configuration

- `agents` — name, status `ACTIVE` or `INACTIVE`.
- `prompt_versions` — immutable prompt text, monotonic version per agent.
- `agent_versions` — immutable model settings, budgets, knowledge base id, prompt version id, full JSON snapshot.
- `tools` — registry. Seeded with the two MVP tools only.
- `agent_version_tools` — allowlist for that version.
- `knowledge_bases` — embedding model id and dimension.

Changing configuration inserts a new version. It does not update the previous row.

## Knowledge

- `documents` — original object key, status, checksum. Status moves `UPLOADED -> PROCESSING -> ACTIVE` or `FAILED`.
- `document_chunks` — text, section, page, embedding, generated `tsvector`.

## Execution

- `agent_runs` — state, snapshot, tokens, estimated cost, error category, timeout. Unique trace correlation.
- `run_events` — ordered timeline. This is what the UI renders and what SSE replays.
- `tool_proposals` — tool, arguments, reason, policy decision. Unique per `(run_id, sequence)`.
- `approvals` — one per proposal. Unique `proposal_id`. Status and expiry.
- `jobs` — queue. Unique `idempotency_key`. Lease columns `locked_by`, `locked_until`.
- `job_attempts` — visible retry history.
- `tool_executions` — unique `idempotency_key` = `{runId}:{proposalId}`.
- `tickets` — unique `idempotency_key`. The support ticket itself.
- `evaluations` — scores for a run, plus the versions of agent, prompt, dataset, and evaluator.
- `audit_events` — append-only record of security-relevant actions.

## Constraints that matter

- `tool_executions.idempotency_key` unique.
- `tickets.idempotency_key` unique.
- `jobs.idempotency_key` unique.
- `approvals.proposal_id` unique.
- `agent_versions (agent_id, version_number)` unique.
- `memberships (workspace_id, user_id)` unique.

## Retention

Defaults are in `docs/data-retention.md`. A scheduled job redacts question and answer text after the configured window. Audit metadata, decisions, and ids are kept longer than prompt content.
