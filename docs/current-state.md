# Current state

Audit date: 2026-10-05. This describes the repository and the Compose stack that was running on this machine. It does not redesign anything.

Operations metrics now follow `docs/metrics-contract.md`: completion and failure use terminal runs, timeouts count as unsuccessful, cancelled runs do not, percentiles stay end-to-end but are labeled and formatted, approved and expired waits are separate, cost carries a pricing status, and queue depth is limited to the caller’s workspace.

## What is running

`docker compose ps` showed Postgres, Redis, Garage, the OpenTelemetry collector, Jaeger, Prometheus, Grafana, the Java control plane, the Python agent runtime, the Python worker, and the Next.js frontend. `garage-init` is a one-shot bootstrap and exits 0.

Host ports in this workspace: UI `3000`, API `8080`, runtime `8090`, Grafana `3001`, Jaeger `16686`, Prometheus `9090`, Postgres `5435`, Redis `6382`, Garage S3 `9010`.

Checked live:

- `GET /readiness` returned `postgres: UP`, `redis: OPTIONAL`, `status: UP`.
- Operator login returned HTTP 200.
- `GET /api/v1/metrics/summary` returned the same numbers shown on the Operations page.

## Frontend

Next.js App Router, React, TypeScript, Tailwind. There is no client store beyond `useState` / `useEffect`. `frontend/lib/api.ts` calls `/backend` (rewritten to the control plane) with cookie credentials.

| Route | What it does |
|---|---|
| `/login` | Email and password. Dev values are prefilled. |
| `/` | Operations metric grid from `/api/v1/metrics/summary`. |
| `/runs/new` | Starts a support run. |
| `/runs/[id]` | Answer, citations, tokens, cancel, timeline. SSE plus a 2.5s poll. |
| `/approvals` | Pending queue. Approve and reject. Polls every 3s. |
| `/agents` | List and activate or deactivate. |
| `/knowledge` | First knowledge base and its documents. Read only. |
| `/evaluations` | Last 100 evaluation rows. |
| `/observability` | Links to Jaeger and Grafana, plus the raw metrics JSON. |
| `/audit` | Latest audit page. |
| `/admin` | Users, settings JSON, policy dry run. |
| `/why` | Public explanation. Outside the console shell. |

There is no runs list, no agent detail, no tool registry page, no settings page separate from admin, and no command palette.

The shell (`frontend/components/Shell.tsx`) shows every link to every signed-in role. The server rejects forbidden calls. The Audit page, opened as Operator, showed “Your role cannot perform this action.” and still rendered empty table headers.

## Control plane

Spring Boot. Controllers:

- `AuthController`: login, logout, me. JWT in the `aegis_session` cookie.
- `RunController`: create, list, detail, timeline, SSE, cancel, approvals, approve, reject.
- `AgentController`: agents, versions, status, tools.
- `KnowledgeController`: list bases, create base, list documents, upload.
- `PlatformController`: audit, metrics, evaluations, users, memberships, settings, policy dry run, failure simulations, jobs.
- `InternalController`: worker tool-result callback with `X-Internal-Token`.
- `HealthController`: `/liveness`, `/readiness`, `/health`.

Policy and the tool gateway are Java packages, not separate services. Jobs are rows in Postgres (`FOR UPDATE SKIP LOCKED`), not a Redis queue.

## AI runtime and worker

- `agent-runtime/app.py` retrieves, answers, and proposes. It cannot insert tickets.
- `worker/worker.py` embeds documents, creates a ticket once per idempotency key, and writes `heuristic-v1` evaluations.
- `py/aegislib` holds chunking, feature-hash embeddings (384 dimensions, `feature-hash-v1`), grounding, and the planner.
- Default model is `grounded-extractive` / `grounded-extractive-v1`. An OpenAI call happens only when a key is set and the provider is `openai`.
- Retrieved document text is not passed into the planner as instructions.

## Database

One Flyway migration, `V1__init.sql`. Tables: users, workspaces, memberships, workspace_settings, login_failures, knowledge_bases, agents, prompt_versions, agent_versions, tools, agent_version_tools, documents, document_chunks (pgvector plus a lexical GIN index), agent_runs, run_events, tool_proposals, approvals, jobs, job_attempts, tool_executions, tickets, evaluations, audit_events.

Seeded local workspace `11111111-1111-1111-1111-111111111111` with four users. Password is the dev seed password. Knowledge page showed 11 ACTIVE documents in `support-policies`.

## Redis

Lettuce pub/sub on channel `aegis.runs` fans SSE events across control-plane instances. Redis is not the session store, not the job queue, and not a response cache. Readiness stays up when Redis is down.

## Observability

The control plane emits OpenTelemetry to the collector, which forwards to Jaeger. Prometheus scrapes `/actuator/prometheus`. Grafana is provisioned with one dashboard. The in-app Observability page does not draw traces. It links out and prints the SQL summary.

## Security

Roles are Developer, Operator, Reviewer, and Admin, loaded from `memberships` on each request. Mutating `/api` calls check an Origin allowlist. Internal callbacks use a shared token. Login lockout is 10 failures in 10 minutes. BCrypt strength is 12.

The UI does not hide links the role cannot use.

## Tests and delivery

Java unit tests cover the policy engine, argument validation, cost rates, and the run state machine. Python tests cover embeddings, grounding, the planner, and retries. `evaluation/run_critical.py` is an offline scenario script. There are no frontend tests, no HTTP integration tests, and no browser end-to-end tests.

GitHub Actions runs those Java tests, the Python tests, the evaluation script, `npm run typecheck`, and `scripts/scan_secrets.py`. It does not build images or deploy.

`infrastructure/aws/main.tf` sketches a VPC, two subnets, RDS Postgres, ElastiCache, S3, a secrets shell, an ECS cluster, and a log group. It has no task definitions, services, or load balancer, and it has not been applied.

## Live workspace snapshot

Taken 2026-10-05 from Postgres after the dashboard was open:

| state | runs | average `ended_at - started_at` |
|---|---|---|
| COMPLETED | 3 | 259.3 s |
| FAILED | 2 | 903.9 s |
| TIMED_OUT | 1 | 59.1 s |

Approvals: 3 APPROVED (average wait 254.2 s) and 3 EXPIRED (average wait 618.8 s). All six runs used `grounded-extractive-v1`. Token sum 3351. Stored cost 0. Evaluations: 3, matching the three completed runs.
