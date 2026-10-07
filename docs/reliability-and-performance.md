# Reliability and performance

## Scope and evidence

This document describes the local Docker Compose architecture. It is a measured development baseline, not a production SLO or a claim of Internet-scale capacity.

The audit covered the Spring control plane, Python runtime, PostgreSQL worker queue, Redis SSE fan-out, PostgreSQL queries and indexes, object storage, Jaeger and Prometheus clients, evaluations, approvals, knowledge processing, and frontend request lifecycles.

Audit classification after the reliability changes:

- Healthy: approval row locking and terminal transitions; evaluation request-key idempotency; tool and ticket unique idempotency keys; bounded list page sizes; liveness/readiness separation; Jaeger response caps; immutable evaluation results; published knowledge-version locking.
- Partial: support-run orchestration is bounded and queued runs recover after a control-plane restart, but a process loss during an in-flight runtime call is recovered by the durable timeout rather than resumed mid-step.
- Partial: document processing is limited to 10 MB and storage reads are bounded, but PDF extraction still holds one bounded document in memory.
- Partial: Redis is optional for cross-instance SSE fan-out. PostgreSQL reconciliation remains authoritative when Redis is unavailable.
- Bottleneck corrected: operations metrics previously loaded every run and approval into Java. PostgreSQL now performs counts, means, and percentiles in bounded-result aggregate queries.
- Bottleneck corrected: evaluation detail previously issued one checks query per result. It now fetches checks in one query and groups them in memory.
- Bottleneck corrected: retrieval previously loaded and sorted every eligible vector in Python. PostgreSQL now selects at most 200 hybrid candidates before deterministic reranking.
- Fragile corrected: a fixed 60-second worker lease could expire during live work, and stale workers could overwrite a new owner. Workers now renew leases and completion/failure updates require matching ownership.
- Fragile corrected: document metadata and its embedding job could commit separately. They now commit in one transaction, with an advisory lock and partial unique checksum index.
- Unbounded corrected: SSE replay and run timelines are capped at 200 events; approval timelines are capped at 100 events.
- Missing corrected: frontend fetches now have a 15-second default timeout, caller cancellation propagation, and stale-request cancellation on the primary data-heavy pages.
- Missing corrected: worker readiness now checks PostgreSQL while worker liveness remains dependency-independent.

## Performance contracts

These are internal local targets, evaluated against like-for-like data:

- Keystrokes, focus, button feedback, and local filtering: no synchronous task over 100 ms.
- Warm bounded list API: p95 at or below 350 ms.
- Operations, observability, and evaluation aggregate API: p95 at or below 500 ms.
- Detail API: p95 at or below 600 ms, excluding Jaeger latency where explicitly reported.
- PostgreSQL list query: warm execution at or below 50 ms on the 5,000-row local fixture.
- PostgreSQL aggregate query: warm execution at or below 150 ms on the 5,000-row local fixture.
- Queue pickup: p95 at or below 1 second while one worker is available and queue depth is below 20.
- Run acceptance: response at or below 1 second; transition from QUEUED to RUNNING at or below 2 seconds while capacity is available.
- Evaluation acceptance: response at or below 500 ms; dispatch begins at or below 5 seconds while capacity is available.
- Retrieval: p95 at or below 750 ms over the 5,000-chunk local fixture.

Latency is separated into user-visible HTTP time, server processing, queue wait, and external dependency time. End-to-end run latency continues to include approval wait under the established metric semantics.

## Bounded APIs and resource protection

- Runs: default 20, maximum 100.
- Approvals and knowledge documents: default 20, maximum 50.
- Audit, traces, evaluations, and safety: fixed 25.
- Retrieval: query maximum 2,000 characters, top K maximum 20, candidate corpus maximum 200, quote maximum 500 characters.
- Knowledge upload and processing: maximum 10 MB.
- Document detail: extracted text maximum 20,000 characters; chunk pages maximum 50.
- Jaeger detail: maximum 200 parsed spans.
- Run event list and SSE replay: most recent 200.
- Approval timeline: most recent 100.
- Approval, audit, and document metadata filters: maximum 200 characters.
- Evaluation suites: maximum 50 cases.

The run executor has 4 core threads, 8 maximum threads, and a 100-item queue. Rejection is explicit `QUEUE_FAILURE`; it is never reported as success. The PostgreSQL worker deliberately processes one job at a time in the local stack, preventing embedding and evaluation work from creating uncontrolled concurrency.

## Queue, retry, timeout, and cancellation model

PostgreSQL is the durable job queue. Claims use `FOR UPDATE SKIP LOCKED`. A job has a bounded attempt count and an owner-specific renewable lease. `finish` and `fail` only mutate `RUNNING` jobs owned by the current worker. Lease expiry is observable and safely requeues work unless its attempt budget is exhausted.

Retry classification:

- Retryable: bounded dependency 5xx, timeout, connection reset/unavailable database, model timeout, and explicitly transient tool failures.
- Non-retryable: validation, authorization/policy denial, malformed arguments, approval expiry/rejection, cancellation, and budget exhaustion.

Retry delay is capped exponential backoff with equal jitter. Side-effecting ticket execution is retried only behind database idempotency keys and unique constraints. Evaluation failure callbacks become separate durable jobs if the first callback is unavailable.

Timeouts:

- Frontend fetch: 15 seconds by default, with per-call override.
- PostgreSQL control-plane statements: 15 seconds; pool acquisition: 3 seconds.
- Runtime connect: 3 seconds; per-run read timeout comes from the immutable run snapshot.
- Runtime PostgreSQL connect: 2 seconds; retrieval statement: 5 seconds.
- Worker callback: 10 seconds; evaluation callback: 30 seconds.
- Worker S3 connect: 3 seconds; read: 15 seconds; 3 total attempts.
- Control-plane S3 attempt: 5 seconds; total call: 12 seconds; 2 retries.
- Jaeger and Prometheus checks retain their short bounded client timeouts.

Cancellation is durable and idempotent at terminal states. Run and approval rows are locked for competing transitions. Tool execution locks the run while committing its idempotent execution and ticket. A later cancellation detects the committed side effect and returns `CONFLICT` instead of hiding it. Evaluation cancellation locks the execution and result rows in one transaction before cancelling child product runs.

## Idempotency and consistency

- Support run creation accepts a client request key. Replays return the original run; a database unique index closes concurrent HTTP retry races.
- Evaluation start uses `(workspace_id, request_key)`.
- Tool proposals, jobs, tool executions, and tickets have unique idempotency keys.
- Approval decisions lock the approval and run; same-decision replay is idempotent and opposite decisions conflict.
- Live document checksum uniqueness is enforced per knowledge base. Metadata, embedding job, and upload audit event commit together.
- Knowledge publication and activation lock the knowledge base and reject incomplete documents.
- Evaluation completion writes result, checks, and safety signals transactionally. Prior terminal results are not overwritten.

## Health and dependency behavior

- Liveness answers whether the process can serve. It does not fail for Jaeger, Prometheus, Redis, object storage, or model-provider outages.
- Readiness checks required PostgreSQL for the control plane, runtime, and worker.
- Redis is optional. Its loss degrades multi-instance event fan-out, while persisted event reconciliation remains available.
- Jaeger and Prometheus failures are shown as `UNAVAILABLE` or `DEGRADED` in observability data and do not fabricate healthy telemetry.
- Runtime, object-storage, and model failures are returned as dependency/runtime errors; they are not converted into model-quality failures.

Reliability error codes include `VALIDATION_ERROR`, `AUTHORIZATION_ERROR`/existing `FORBIDDEN`, `POLICY_DENIED`, `NOT_FOUND`, `CONFLICT`, `TIMEOUT`, `DEPENDENCY_UNAVAILABLE`, `QUEUE_FAILURE`, `WORKER_FAILURE`, `RUNTIME_FAILURE`, `DATABASE_FAILURE`, `CANCELLED`, and `INTERNAL_ERROR`. Public responses do not include stack traces.

## Telemetry

Existing bounded-cardinality telemetry is retained. Added signals cover run-executor queue depth and active work, rejected submissions, expired job leases, approval expiry, and run timeouts. Queue depth remains a gauge. IDs are span/log fields where useful and are not Prometheus labels.

## Local data and measurements

The preserved development database before synthetic scale validation contained 64 runs, 555 run events, 21 approvals, 328 audit events, 5 evaluation executions, 85 evaluation results, 82 safety signals, 12 documents, 14 chunks, and 66 jobs.

`scripts/reliability_scale.sql` runs in a transaction and rolls back. It adds 5,000 runs, 50,000 run events, 5,000 audit events, 500 documents, and 5,000 chunks, analyzes the affected tables, and captures `EXPLAIN (ANALYZE, BUFFERS)` for critical list, event, audit, and vector-candidate queries.

Measured PostgreSQL 16 results on the local fixture:

- Run list, 20 rows from 5,064 total: 0.772 ms execution, 8 shared-buffer hits, workspace/created index scan.
- Event replay, 10 rows from 50,555 total: 10.208 ms execution, 13 shared-buffer hits, `(run_id, sequence)` bitmap index scan.
- Audit list, 25 rows from 5,328 total: 0.209 ms execution, 4 shared-buffer hits, workspace/created index scan.
- Vector candidates, 100 rows from 5,014 chunks: 3.998 ms execution, 824 shared-buffer hits, HNSW index scan.
- Fixture writes were deliberately measured but rolled back: 5,000 runs in 5.291 seconds, 50,000 events in 5.198 seconds, 5,000 audit events in 4.845 seconds, and 5,000 vector chunks in 19.193 seconds.

`scripts/reliability_benchmark.py` records first request, warm p50/p95/p99, response bytes, error and timeout rates, a bounded concurrent burst, and a sustained audit-list soak. The checked-in JSON report records the exact local result.

Measured authenticated API results with 20 warm samples per endpoint:

- Operations overview: p50 266.63 ms, p95 463.57 ms, p99 610.28 ms, 26,295–26,615 bytes.
- Support run list/detail: p95 206.79/98.46 ms, 4,954/2,309 bytes.
- Approval list/detail: p95 301.61/103.52 ms, 13,912/4,017 bytes.
- Knowledge base/documents: p95 198.12/156.94 ms, 300/3,353 bytes.
- Observability overview: p95 301.68 ms, 8,808 bytes.
- Trace list/detail: p95 173.50/768.04 ms, 7,411/77,748 bytes. Trace detail includes bounded Jaeger dependency time.
- Audit list/detail: p95 191.25/193.08 ms, 12,197/535 bytes.
- Evaluation overview/history/detail: p95 98.62/79.55/536.31 ms, 3,746/2,511/12,600 bytes.
- Safety overview: p95 95.60 ms, 7,833 bytes.
- Support-run creation: 895.03 ms first acceptance; identical request-key replay returned the same run in 95.98 ms.
- Operations burst: 100 requests at concurrency 10, 14.99 requests/second, p50 623.31 ms, p95 1,071.30 ms, p99 1,232.01 ms, 0 errors and 0 timeouts.
- Audit soak: 60.31 seconds, 1,308 requests at concurrency 4, 21.69 requests/second, p50 155.68 ms, p95 399.64 ms, p99 600.58 ms, 0 errors and 0 timeouts.

## Recovery and known limits

- A worker crash is recovered through lease expiry and owner-safe retry. Completed idempotent ticket writes are not duplicated.
- A queued support run is resubmitted after control-plane restart in batches of 50. A run interrupted after entering an orchestration step is not replayed blindly; its durable timeout produces an honest terminal state.
- A temporary callback outage is retried as durable callback work.
- Approval wait does not consume tool-execution time. Approval atomically resets `timeout_at` from the immutable run snapshot before entering `TOOL_EXECUTING`.
- Object bytes are written before metadata transaction commit. The deterministic checksum-based object key makes concurrent identical uploads converge on one object, but an unrelated database outage can leave an unreferenced object that requires lifecycle cleanup.
- Offset pagination is retained for API compatibility. It is bounded but deep pages are less efficient than cursor pagination.
- The local worker concurrency of one favors safety over throughput. Raising it requires repeating lease, database-pool, and workload-isolation measurements.

## Concurrency, cancellation, and restart validation

The following checks used the preserved local development database and isolated test records:

- Worker crash during a 30-second job: the job was observed `RUNNING`, attempt 1, with a live owner lease. The worker was killed. After lease expiry the maintenance task changed it to `RETRY` with `last_error = 'lease expired'`. A restarted worker completed it as `SUCCEEDED`, attempt 2. The stale owner could not commit completion.
- Control-plane crash with a persisted `QUEUED` run: the row remained `QUEUED` with no start event while the process was down. After restart, bounded recovery claimed it once and it completed through seven ordered lifecycle events.
- Opposite approval decisions: simultaneous approve/reject requests produced one HTTP 200 approval and one HTTP 409 `APPROVAL_ALREADY_APPROVED`; one ticket was created. This test exposed an expired pre-approval execution deadline. After correction, an `APPROVAL_REQUIRED` run whose deadline was deliberately moved one minute into the past reset its deadline on approval and completed with exactly one ticket.
- Duplicate evaluation start: two concurrent HTTP requests with the same request key both returned HTTP 202 and execution `91b54081-43c7-445d-8bee-d3082a7b1ebe`. PostgreSQL contained one start job. The execution completed 17/17 scored results with zero infrastructure errors.
- Queued evaluation cancellation: with the worker stopped, execution `3289fc42-7a1f-4807-bdfb-aa5955da6539` was cancelled while `QUEUED`. All 17 result rows became `CANCELLED`, no product runs were created, and worker restoration did not revert the terminal state.
- Support-run request replay returned the original run ID. The measured first acceptance was 895.03 ms and the same-key replay was 95.98 ms.
- The JDBC concurrency suite exercised a stale worker owner, simultaneous opposite decisions, and cancellation racing a committed tool side effect against an isolated PostgreSQL database.

## Controlled dependency failure and recovery

- PostgreSQL down: control-plane and runtime liveness remained HTTP 200; their readiness endpoints returned HTTP 503 with PostgreSQL `DOWN`. The worker returned HTTP 200 for liveness and HTTP 503 for readiness. All became ready again after PostgreSQL restoration.
- Redis down: control-plane readiness remained HTTP 200 and reported Redis as `OPTIONAL`; persisted PostgreSQL reconciliation stayed authoritative. Redis restarted cleanly.
- Jaeger and Prometheus down: Observability explicitly rendered **Trace backend unavailable** and **Metrics unavailable** while continuing to show PostgreSQL product metrics. Refresh after restoration removed both warnings.
- Runtime down: a run exhausted three bounded attempts and terminated as `RUNTIME_FAILURE`, not a model-quality failure. A provider-originated HTTP 504 remains separately classified as `MODEL_TIMEOUT`.
- Object storage down: document upload returned HTTP 503 `DEPENDENCY_UNAVAILABLE`; no document metadata row committed. After restoration, a bounded `HEAD` of an existing 121-byte object succeeded.
- Worker down: evaluation work remained durably queued and could be cancelled. After restart, the cancelled execution remained terminal.

## Browser and accessibility validation

The production Next.js build was exercised at 1920, 1024, and 390 CSS pixels. Operations, Support Run, Approvals, Observability, Audit, Evaluation, and Safety all reported `scrollWidth <= innerWidth`; no horizontal page overflow was observed.

At 390 pixels the sidebar became an **Open navigation** control. Opening it moved focus to the first link, and Escape closed it. Slow background refresh retained usable stale content and exposed a disabled, busy **Refreshing…** control. A transient session-check timeout now presents an in-place retry state and does not clear the authenticated session.

Captured evidence:

- `docs/evidence/reliability/operations-desktop.png`
- `docs/evidence/reliability/loading-state.png`
- `docs/evidence/reliability/degraded-dependencies.png`
- `docs/evidence/reliability/dependency-recovery.png`
- `docs/evidence/reliability/long-running-evaluation.png`
- `docs/evidence/reliability/tablet-1024.png`
- `docs/evidence/reliability/mobile-390.png`

## Validation commands and results

- `docker run --rm -e AEGIS_TEST_JDBC_URL=jdbc:postgresql://host.docker.internal:5435/aegis_reliability_test -e AEGIS_TEST_DB_USER=aegis -e AEGIS_TEST_DB_PASSWORD=change-me-postgres-dev-only -v "D:\newProject:/src" -v aegis-m2:/root/.m2 -w /src maven:3.9.11-eclipse-temurin-21 mvn -B -f control-plane/pom.xml test` — 114 tests, 0 failures, 0 errors, 0 skipped.
- `python -m pytest py/aegislib/tests -q` — 23 passed.
- `python evaluation/run_critical.py` — 30 cases, 24 Python-executed, 6 Java policy fixtures, no failures.
- `python scripts/scan_secrets.py` — no matches.
- `npm test` — 10 passed.
- `npm run typecheck` — passed.
- `npm run build` — production build passed; 20 static pages generated.
- `docker compose exec -T postgres psql -v ON_ERROR_STOP=1 -U aegis -d aegistrace -f /tmp/reliability_scale.sql` — representative fixture and all four `EXPLAIN (ANALYZE, BUFFERS)` statements passed, then rolled back.
- `python scripts/reliability_benchmark.py --samples 20 --burst 100 --concurrency 10 --soak-seconds 60 --include-run-start --output evaluation/reliability-benchmark.json` — 100-request burst and 60.31-second soak completed with zero errors and zero timeouts.
- `git diff --check` — passed after final documentation and evidence updates.

The resource probe sampled the three request-processing containers before and after another 50-request burst plus 15-second soak. Control-plane memory moved from 513.1 MiB to 575.5 MiB and PIDs from 62 to 63 as the JVM warmed; runtime stayed at 56.93 MiB and 3 PIDs; worker moved from 43.64 MiB to 43.89 MiB and stayed at 3 PIDs. This short local probe found no Python process/thread growth, but it is not a production-duration heap-leak proof.
