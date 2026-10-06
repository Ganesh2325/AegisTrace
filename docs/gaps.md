# Gaps

Each gap is something the current product does not do, or does in a way that will mislead an operator. Metric-definition issues below are closed. Product-surface gaps remain.

## Data

Earlier versions divided completion by every run, ignored timeouts in failure, showed raw millisecond percentiles, mixed approved and expired approval waits, showed `$0.0000` with no status, and counted queue jobs without a workspace. The current definitions are in `docs/metrics-contract.md`.

Still true:

- p50, p95, and p99 remain end-to-end `ended_at - started_at`, so a slow approval still makes latency look large. That is now labeled. Agent execution time is still not a separate stored column.
- The token columns are `NOT NULL`. A stored zero cannot be distinguished from “the model did not report usage” on an individual run. The summary uses `NO_TOKENS` when the workspace sum is zero.
- Unknown models still store a zero cost on the run row. The summary status is `PRICING_UNAVAILABLE` and the card does not present that zero as a price.
- The Prometheus queue gauge is still process-wide. The Operations number is workspace-scoped.
- Metrics are all-time. There is no selected period.

## Product surfaces

- No run list. Dashboard cards do not open a run or an approval.
- Approval UI loads `status=PENDING` only. Approved, rejected, expired, and cancelled are not tabs.
- Agent page cannot open a version, prompt, tool assignment, budget, or timeout.
- No tools or policy history page. Policy dry run is an admin form.
- Knowledge UI cannot upload, open a document, or inspect chunks. `POST /api/v1/knowledge-bases/{id}/documents` exists.
- No retrieval inspector.
- Evaluation page lists rows. It does not browse the offline dataset or compare versions.
- Observability does not search traces. Jaeger is a new tab at `localhost:16686`.
- Audit is a single unfiltered page of 50 rows. No security-event view.
- Navigation is not grouped, not searchable, and not limited by role.

## Quality and delivery

- No metric unit tests and no API test for `/metrics/summary`.
- No Playwright or equivalent end-to-end suite.
- CI does not build the Compose images and does not deploy.
- AWS Terraform is not a running environment.
- Grafana’s provisioned dashboard was not queried against live Prometheus in this audit.

## Security gaps that are already designed and not fully demonstrated

- Negative RBAC is enforced by the API (Operator saw the audit 403) and is not reflected in the navigation.
- Prompt-injection cases exist in the offline evaluator. This audit did not replay them against the live stack.
- Shared database credentials for the API and the worker remain.
