# Operations command center

The Overview page is an operations console for one authenticated workspace. It is not a BI product and it does not invent alerts.

## Data sources

| Surface | Source |
| --- | --- |
| KPI summary | `MetricsCalculator.summarize` via `MetricsService.summary` |
| Active / recent / failed runs | `agent_runs` joined to `agents`, workspace + role visibility |
| Approvals | `approvals` + `tool_proposals`. Returned only for Reviewer and Admin |
| Activity | `run_events` newest 20 |
| Agent activity | `agents` with counts of visible runs in the window |
| Activity trend | `date_trunc` of `agent_runs.created_at` |
| Latency trend | `percentile_cont` of valid terminal durations, at least two buckets |
| Queue | Postgres jobs in `PENDING`, `RETRY`, `RUNNING` for this workspace |

`GET /api/v1/metrics/summary` remains all-time and unfiltered by role visibility. Observability still uses that endpoint. Overview uses `GET /api/v1/operations/overview`.

## Window

Query parameter `window`: `24H`, `7D`, `30D`, `ALL` (aliases `ALL_TIME`, `1D`, `168H`, `720H`).

A bounded window is a **rolling duration in UTC**, not calendar days:

- `24H` = previous 86 400 seconds
- `7D` = previous 168 hours
- `30D` = previous 720 hours
- `ALL` = every stored row (`windowStart` is null)

The predicate is `created_at >= windowStart AND created_at < windowEnd` for runs and activity, `requested_at` for approvals, `ended_at` for the latency trend. Queue depth is current open jobs and is not windowed.

Default UI range is `ALL` so the live demo continues to show all-time numbers unless the operator changes the control. The selected range is in `?range=` with `?status=` and `?page=`.

There is no previous-period comparison. The workspace does not have enough independent history to compute one without inventing a delta.

Timezone on the payload is `UTC`. The UI formats instants for reading.

## Role visibility

The same SQL as `RunService.list`:

- Operator: own `user_id`
- Reviewer: runs that have an approval row
- Developer and Admin: every run in the workspace

Overview KPIs for Operator and Reviewer therefore describe **visible** runs in the window, not the whole tenancy. Pending approval **counts** still come from workspace `approvals`. Approval **payloads** are omitted unless the role is Reviewer or Admin.

## Attention rules

Deterministic, documented, no invented risk score:

1. `HIGH_LATENCY` when `latency.high` (p95 ≥ 300 000 ms)
2. `PENDING_APPROVAL` when pending count &gt; 0 and the role received approval rows
3. `TIMED_OUT` when timed-out count &gt; 0
4. `FAILED` when failed count &gt; 0
5. `QUEUE` when open jobs ≥ 5
6. `ACTIVE_APPROVAL` for each visible non-terminal run in `APPROVAL_REQUIRED`

Empty workspace: no attention items, no charts, CTA to start a support run when the role can create one.

Latency trend with fewer than two buckets: textual “not enough” state, no drawn series.

## Refresh

Manual Refresh. Concurrent clicks share one in-flight request. The page is not labelled LIVE. `generatedAt` is shown as “Updated …”.

## Queries

All overview SQL binds `:workspace`. Bounded `LIMIT` 20 / 10. Order `created_at DESC, id DESC`. Index already present: `agent_runs_workspace_created_idx`, `approvals_queue_idx`, `jobs_workspace_open_idx`. No new index: the current dataset is a handful of rows.

System health of Redis, workers, and the runtime is not on this page. Those are not measured here.
