# Architecture

The AI proposes. Deterministic software decides. Humans approve sensitive actions. Workers execute safely. Observability records what happened. Evaluation checks whether it worked.

```text
Browser (Next.js)
    |  HTTPS via same-origin rewrite, SSE for run events
    v
Spring Boot control plane
    |-- PostgreSQL (system of record, pgvector, jobs, audit)
    |-- Redis (optional fan-out for SSE, cache). Not the source of truth.
    |-- S3 / MinIO (original documents)
    |
    |  HTTP, internal token, W3C trace context
    v
Python agent runtime
    retrieval, grounded or OpenAI-compatible generation, tool proposal
    |
    v
Control plane policy engine
    ALLOW | DENY | REQUIRE_APPROVAL | REQUIRE_ADMIN_APPROVAL
    |
    v
Approval row (pause)
    |
    v
Postgres job queue  <-- worker lease, retry, dead letter
    |
    v
Python worker
    idempotent ticket insert, embeddings, evaluation
```

Policy and approval live in the control plane because they are authorization. They are not a separate deployable just to add a box to the diagram. The runtime cannot insert a ticket. The worker cannot insert a ticket unless an approval id and idempotency key exist.

## Service boundaries

| Module | Owns | Must not own |
|---|---|---|
| Frontend | Rendering, input, SSE subscription | Authorization decisions |
| Control plane | Identity, RBAC, versions, policy, approvals, run state, audit, job enqueue | Vector math, PDF parsing |
| Agent runtime | Retrieval, answer composition, proposal | Writes, policy, approval |
| Worker | Embedding, ticket execution, evaluation jobs | Policy decisions |
| PostgreSQL | Durable state and the job queue | — |
| Redis | Cross-instance event fan-out | Jobs, approvals, tickets |
| Object storage | Original uploaded bytes | Embeddings |

## Why the queue is Postgres

A job is created in the same transaction as the approval decision. Redis being down cannot lose that decision or create a second one. Workers claim rows with `FOR UPDATE SKIP LOCKED`. Redis publishes run events so more than one API instance can push SSE. If Redis is down, the instance that handled the request still emits locally, and the UI falls back to reading the run from Postgres.

## Why the runtime is a separate process

Model calls, PDF extraction, and embedding are slow and have a different dependency set from the API. The API returns as soon as the run row is committed. Orchestration continues on a bounded pool. The browser never waits on the model.

## Correlation

Every request carries or receives `request_id`. Every run stores `trace_id`, `run_id`, `workspace_id`, and `user_id`. Logs that are allowed to include them do. Prompt text, retrieved text, tool arguments, and model output are not written to logs unless `AEGIS_CONTENT_LOGGING=true`.

## Snapshot

When a run starts, the control plane copies agent version, prompt version, model, model settings, tool allowlist, policies, knowledge-base id, embedding model, and budgets onto the run. Later edits create a new version. They do not update the snapshot.
