# Architecture as implemented

## Current architecture

```text
Browser (Next.js)
    cookie JWT
        |
        v
Control plane (Spring Boot)
    auth, RBAC, versions, policy, approvals, run state, audit, jobs, SSE
        |                         |                    |
        | SQL                     | HTTP               | optional Redis pub/sub
        v                         v                    v
PostgreSQL + pgvector      Agent runtime         other control-plane instances
        ^                  (retrieve, answer,
        |                   propose only)
        |
Worker (embed, idempotent ticket, heuristic evaluation)
        |
        v
Garage (S3-compatible object storage)

OpenTelemetry collector -> Jaeger
Prometheus -> Grafana
```

The AI proposes. `PolicyEngine` decides. A human approves a write. The worker executes. Postgres stores the run, the timeline, the audit row, and the evaluation.

One workspace is seeded. Agent versions are immutable rows. A run stores `agent_version_id`, `prompt_version_id`, and a JSON snapshot.

Run states in the database check constraint: QUEUED, RUNNING, RETRIEVING, THINKING, TOOL_PROPOSED, APPROVAL_REQUIRED, APPROVED, REJECTED, TOOL_EXECUTING, COMPLETED, FAILED, CANCELLED, TIMED_OUT. Terminal states are COMPLETED, FAILED, CANCELLED, and TIMED_OUT. REJECTED can move to COMPLETED and cannot move to TOOL_EXECUTING.

Tools in the registry: `search_knowledge` (read, no approval) and `create_support_ticket` (write, approval required).

## Target architecture

The target in the existing ADRs and in `infrastructure/aws/main.tf` keeps this same split:

- Next.js UI
- Spring Boot control plane
- Python runtime and worker
- PostgreSQL with pgvector
- Redis only for SSE fan-out
- S3 for documents
- OpenTelemetry, Prometheus, Grafana, Jaeger
- GitHub Actions
- AWS via ECS, RDS, ElastiCache, and S3

Kubernetes and a multi-agent runtime are explicitly out of scope for the early phases.

The product target adds trustworthy metrics, a design system, an operations workspace, explainable RAG, an approval center with history, agent and policy administration screens, evaluation comparison, an in-app trace view, immutable audit exploration, and a deployment that has actually been applied.

## Differences

| Area | Current | Target |
|---|---|---|
| UI | Dark utility pages, one shell, role-blind navigation | Design system, role-aware shell, operations workspace |
| Metrics | One SQL summary, wall-clock percentiles, `$0.0000` for the extractive model | Formulas that separate completion, failure, timeout, and human wait |
| Runs | Create and detail. No list page | List, detail, and click-through from the dashboard |
| Approvals | Pending queue only | Pending, approved, rejected, expired, cancelled |
| Agents | List and status toggle | Version, prompt, tool, policy, budget, and timeout detail |
| Tools | API `GET /tools` and an admin dry run | Registry and decision history UI |
| Knowledge | Seeded table | Upload, chunks, embeddings, retrieval inspector |
| Evaluation | Per-run heuristic row | Dataset, scenarios, version comparison |
| Observability | External Jaeger and Grafana links | Trace explorer tied to a run id |
| Cloud | Terraform sketch, not applied | A real account deploy after local correctness |
| CI | Unit tests and typecheck | Images, integration, end-to-end, and a smoke check |

## Risks

- Dashboard “p50 / p95 / p99” are end-to-end durations, so a slow human approval looks like model latency.
- Completion is completed divided by every run, and failure counts only `FAILED`. A timeout is in neither rate except as a drag on completion.
- Approval wait averages every decided approval, including expiry.
- Queue depth counts jobs in the whole database, not one workspace.
- The worker and the API share one database role, so a stolen worker password can insert tickets.
- Feature-hash retrieval misses paraphrases that do not share vocabulary with the corpus.
- The shell offers Audit and Admin to an Operator. The API returns 403, and the page still paints an empty table.
- Dev `change-me-*` secrets are the Compose defaults. They are acceptable only on a private machine.
- SSE plus a 2.5s poll can apply events before the surrounding transaction is the only source of truth the UI uses. The poll repairs that, at the cost of extra requests.

## Missing capabilities

Compared with the later phases, these are absent as product surfaces even when a backend piece exists:

- Runs index and dashboard drill-down
- Approval history filters
- Agent version inspector
- Tool and policy history UI
- Document upload and chunk inspector in the UI (upload exists on the API)
- Retrieval test bench
- Evaluation dataset browser and version compare
- In-app waterfall
- Security event explorer
- Command palette, workspace switcher, notifications
- Browser end-to-end tests
- Applied AWS infrastructure
