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

Control plane sampling probability is `1.0`. The control plane exports OTLP HTTP to `http://otel-collector:4318/v1/traces`. The Python runtime and worker export to the same collector when `OTEL_EXPORTER_OTLP_ENDPOINT` is set (compose uses `http://otel-collector:4318`; the Python exporter appends `/v1/traces`). The collector forwards to Jaeger. Python does not install a Prometheus meter provider. FastAPI's automatic trace, metric, and log exporters are disabled so the runtime exports only the spans created in code. `OTEL_TRACES_EXPORTER`, `OTEL_METRICS_EXPORTER`, and `OTEL_LOGS_EXPORTER` are `none` for that automatic path. The explicit OTLP span processor still exports when `OTEL_EXPORTER_OTLP_ENDPOINT` is set.

Spans are created only around real work. The UI does not invent spans or durations for a component that did not record one.

## Trace propagation

Synchronous HTTP uses W3C `traceparent` (`00-{traceId}-{spanId}-{flags}`). All-zero ids are rejected.

1. Control plane starts `aegistrace.agent.run` and stores that span's `trace_id` and `span_id` on `agent_runs`. The span ends when orchestration returns, including when the run is left in `APPROVAL_REQUIRED`. The approval wait is not an open span.
2. `aegistrace.runtime.plan` is a child of the run span. `RuntimeClient` injects the current span's `traceparent` on `POST /v1/plan` and `POST /v1/retrieve`. The runtime continues that context: `aegistrace.runtime.execute` (or `aegistrace.runtime.retrieve`) is a child of the client span.
3. Asynchronous jobs are not an HTTP continuation of Postgres or Redis. The job payload carries the stored run `traceId` and `spanId`. The worker starts `aegistrace.worker.job` with that remote parent (`CHILD_OF` the already-ended run span). `aegistrace.tool.execute` is a child of the worker span and only wraps real ticket execution. The worker callback to the control plane sends the worker span's `traceparent`, so that HTTP request is a child of the tool work and ends with the request.
4. If `span_id` is missing (runs created before the column existed, or a NOOP tracer), the worker starts a new trace. It still sets `run.id`. It does not invent a parent. A span link is not used: each job has one causing run span, so a remote parent is the correct relationship.

## Span names

Control plane, when that work runs:

- `aegistrace.agent.run`
- `aegistrace.configuration.load`
- `aegistrace.policy.evaluate`
- `aegistrace.runtime.plan`
- `aegistrace.evaluation.enqueue`

AI runtime, when that work runs:

- `aegistrace.runtime.execute` — plan request
- `aegistrace.runtime.retrieve` — ad-hoc retrieval request
- `aegistrace.retrieval.load` — chunk SQL load
- `aegistrace.retrieval.search` — hybrid rank
- `aegistrace.vector.search` — embedding cosine rank
- `aegistrace.lexical.search` — token overlap rank
- `aegistrace.runtime.ground` — local grounding. `model.invoked=false`
- `aegistrace.model.generate` — only when the OpenAI HTTP call actually runs

Worker:

- `aegistrace.worker.job` — dequeue/process boundary for embed, tool, evaluation, and simulate jobs
- `aegistrace.tool.execute` — only `EXECUTE_TOOL`

Older traces keep their original operation names (`agent.run`, `llm.generate`, `retrieval.search`, and others). The waterfall renders stored names, services, offsets, and durations. It does not rename them.

## Attributes and privacy

Python span attributes are an allowlist: `run.id`, `workspace.id`, `agent.id`, `agent.version_id`, `knowledge.version_id`, `tool.name`, `policy.decision`, `policy.code`, `service`, `environment`, `status`, `job.type`, `retrieval.strategy`, `retrieval.chunk_count`, `retrieval.candidates`, `model.provider`, `model.name`, `model.invoked`, `error.type`.

The control plane drops blocked tag keys before the API returns them. `error.type` is the exception class name only.

Not recorded on spans: raw prompts, questions, model responses, retrieved document or chunk text, quotes, tool argument maps, secrets, API keys, authorization headers, request bodies, or `user.id`.

## High-cardinality metrics

Prometheus tags stay bounded (`service=control-plane` on the control-plane scrape). Python exports traces only. Do not add `run_id`, `trace_id`, `user_id`, `approval_id`, `document_id`, `chunk_id`, or `request_id` as Prometheus labels. Those identifiers stay on spans and relational rows.

## Trace list vs detail

The explorer lists workspace `agent_runs` that stored a `trace_id` (25 per page, server filtered). Span trees are not embedded in the list. Detail loads one run, its product events, and one Jaeger fetch (spans capped at 200). A parent that is outside that cap is shown as a root rather than a synthesized parent. Missing Jaeger data is `UNAVAILABLE` or `EMPTY`, not a successful empty waterfall pretending to be zero errors.

## Correlation identifiers

| ID | When it exists |
|---|---|
| `run_id` | Every agent run |
| `trace_id` | Stored on `agent_runs` from the control-plane span context (or a hex fallback if the tracer id is blank) |
| `span_id` | The `aegistrace.agent.run` span id, used as the async parent |
| `audit_event_id` | Each insert into `audit_events` |
| `approval_id` | When policy requires a human |
| `tool_proposal_id` | When the runtime proposes a tool |
| `agent_version_id` | Snapshot at run create. Views show this version, not the later active version |
| `knowledge_version_id` | Snapshot at run create when the agent pinned a corpus |

Run timeline, trace waterfall, and audit history stay separate. Links use these stored ids. The UI does not guess a trace from a similar timestamp or name.

Three records:

- Run: business lifecycle (`run_events`)
- Trace: technical spans (Jaeger)
- Audit: governance history (`audit_events`)

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
- Jaeger responds `404` for a stored trace id: detail status `EMPTY` (no spans stored). That is not an outage and not a zero-error success.
- Prometheus down: `Metrics unavailable`. Overview counters still come from Postgres, not fabricated zeros from scrape failure.
- Audit does not depend on Jaeger or Prometheus.

## Performance

List endpoints page at 25. Trace detail loads one run, its events, and one Jaeger fetch (spans capped at 200). Audit list returns summaries; detail returns sanitized metadata.

## Known limitations

- `aegistrace.model.generate` is absent when no model HTTP call runs. Local grounding stays on `aegistrace.runtime.ground`.
- Jobs for runs that have no stored `span_id` start a new trace. They are correlated by `run.id` only.
- The Python exporter batches spans. A trace can take a few seconds to appear in Jaeger.
- Jaeger in local compose does not survive a process restart. Audit and run rows do.
- Python does not scrape Prometheus. Queue and product metrics remain the control-plane SQL formulas.

## External links

Jaeger deep links are `{configured public base}/trace/{hexTraceId}` only when the id is 16–32 hex characters. Grafana remains an external viewer.
