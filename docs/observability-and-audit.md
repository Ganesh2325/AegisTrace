# Observability and audit

Observability answers how the system behaved. Audit answers who changed, approved, or decided what. They share correlation identifiers. They are not the same event stream.

## Architecture

| Concern | Source of truth | In-app API |
|---|---|---|
| Product run lifecycle | `run_events` in PostgreSQL | run timeline, product phases on a trace |
| Workspace metrics | Canonical SQL formulas (same as operations) | `GET /api/v1/observability/overview` |
| Distributed traces | OpenTelemetry → collector → Jaeger | `GET /api/v1/observability/traces/{traceId}` spans |
| Scrape metrics | Prometheus on `/actuator/prometheus` | Health only. Not used as a trace or audit database |
| Dashboards | Grafana | External link |
| Governance history | `audit_events` | `GET /api/v1/audit` and `GET /api/v1/audit/{id}` |

There is no second copy of Jaeger spans in Postgres.

## OpenTelemetry

Control plane sampling probability is `1.0` in the current configuration. Traces export over OTLP HTTP to the collector, then Jaeger.

Python AI runtime and the worker do **not** export OpenTelemetry. They correlate with `run_id` and `trace_id` on the plan HTTP body and on job payloads.

Do not claim 100% span coverage for retrieval or model internals. A nested `retrieval.search` span is not emitted around a separate vector call; retrieval and generation happen in one runtime HTTP call, instrumented as `aegistrace.runtime.plan`.

## Span names

Emitted by the control plane when a run executes:

- `aegistrace.agent.run`
- `aegistrace.configuration.load`
- `aegistrace.policy.evaluate`
- `aegistrace.runtime.plan`
- `aegistrace.evaluation.enqueue`

Approval waiting is **not** an open HTTP span. It is a product event (`APPROVAL_REQUIRED` → decision) because the wait can last minutes.

## Attributes and privacy

Safe attributes include `run.id`, `workspace.id`, `tool.name`, `policy.decision`, `policy.code`, `knowledge.version_id`.

Not recorded: raw prompts, full model responses, document content, tool argument maps, secrets, API keys, `user.id` as a span tag.

## High-cardinality metrics

Prometheus tags stay bounded (`service=control-plane`). Do not add `run_id`, `trace_id`, `user_id`, `approval_id`, or document ids as Prometheus labels. Those belong on traces, logs, and relational rows.

## Trace list vs detail

The explorer lists workspace `agent_runs` that stored a `trace_id` (25 per page, server filtered). Spans load only on the detail route. Missing Jaeger data is `UNAVAILABLE` or `EMPTY`, not a successful empty waterfall pretending to be zero errors.

## Correlation identifiers

| ID | When it exists |
|---|---|
| `run_id` | Every agent run |
| `trace_id` | Stored on `agent_runs` from the control-plane span context (or a hex fallback if the tracer is NOOP) |
| `audit_event_id` | Each insert into `audit_events` |
| `approval_id` | When policy requires a human |
| `tool_proposal_id` | When the runtime proposes a tool |
| `agent_version_id` | Snapshot at run create. Views show this version, not the later active version |
| `knowledge_version_id` | Snapshot at run create when the agent pinned a corpus |

Workers are asynchronous. The execute-tool job payload includes `runId`, `proposalId`, `approvalId`, `workspaceId`, and `traceId`. That is the correlation across the queue, not a continued HTTP trace.

## Audit model

Insert-only. `UPDATE` and `DELETE` on `audit_events` raise from a database trigger. There is no edit or delete API. Actor identity comes from the authenticated session or `null` for system/worker jobs. Role shown in the UI is the **current** workspace membership for that user, not a historical snapshot (the table does not store role).

Results: `SUCCESS` for most actions, `DENIED` for `POLICY_DECISION` with `decision=DENY`, `FAILED` for `LOGIN_FAILED`. Existing action names are kept (`POLICY_DECISION`, not a duplicate `POLICY_DENIED` row).

Metadata is sanitized before API return.

## Workspace isolation

Trace and audit queries are bound to the caller’s workspace. A foreign `trace_id` or audit id returns `404`. Operators only see their own runs; reviewers only see runs that have an approval. Cross-tenant `X-Workspace-Id` is `403`.

## Roles

| Role | Observability | Traces | Audit | Sensitive tool arguments |
|---|---|---|---|---|
| Operator | Yes, own runs | Own runs | No | No |
| Reviewer | Yes, runs with approvals | Those runs | No | Yes on approvals |
| Developer | Yes | Yes | Yes | Yes |
| Admin | Yes | Yes | Yes | Yes |

## Retention

Postgres product events and audit rows follow ordinary database retention (no extra engine in this increment). Jaeger retention is whatever the compose Jaeger instance keeps (in-memory / default process lifetime for local compose). When Jaeger is down, audit and run metadata remain.

## Time range

Observability uses rolling UTC hours: `1H`, `24H`, `7D`, `30D` with the same window math as operations (`24H` = 24 hours, `7D` = 168 hours). Audit defaults to `7D` and also accepts `ALL`.

## Failure behavior

- Trace backend down: section `Trace backend unavailable`. Product metrics still render.
- Prometheus down: `Metrics unavailable`. Overview counters still come from Postgres, not fabricated zeros from scrape failure.
- Audit does not depend on Jaeger or Prometheus.

## Performance

List endpoints page at 25. Trace detail loads one run, its events, and one Jaeger fetch (spans capped at 200). Audit list returns summaries; detail returns sanitized metadata.

## External links

Jaeger deep links are `{configured public base}/trace/{hexTraceId}` only when the id is 16–32 hex characters. Grafana remains an external viewer.
