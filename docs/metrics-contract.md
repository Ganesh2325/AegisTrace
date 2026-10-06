# Metrics contract

The window on `GET /api/v1/metrics/summary` is still **all time**. Overview can request a rolling UTC window through `GET /api/v1/operations/overview?window=`; see `docs/operations-command-center.md`. Timestamp subtraction remains UTC.

Percentiles use one algorithm: PostgreSQL `percentile_cont`, linear interpolation. Rank is `1 + p * (n - 1)` on the ordered sample (one-based). The Java method `MetricsCalculator.percentileCont` is that algorithm. Checked against Postgres 16 on 1000, 2000, … 10000 ms: p50 = 5500, p95 = 9550, p99 = 9910.

Empty rates are `status: NO_DATA` and `value: null`. They are not 0%. A real zero rate is `status: OK` and `value: 0`.

| Metric | Definition | Included states | Excluded states |
|---|---|---|---|
| Runs | Count of `agent_runs` in the caller’s workspace | Every state | Other workspaces |
| In progress | Active runs plus runs waiting for approval | Active states and `APPROVAL_REQUIRED` | Terminal states |
| Terminal runs | Finished runs | `COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT` | `QUEUED`, `RUNNING`, `RETRIEVING`, `THINKING`, `TOOL_PROPOSED`, `APPROVAL_REQUIRED`, `APPROVED`, `REJECTED`, `TOOL_EXECUTING` |
| Completed | Count of `COMPLETED` | `COMPLETED` | Every other state |
| Failed | Count of `FAILED` only | `FAILED` | `TIMED_OUT`, `CANCELLED`, and non-terminal |
| Timed out | Count of `TIMED_OUT` | `TIMED_OUT` | `FAILED`, `CANCELLED` |
| Cancelled | Count of `CANCELLED` | `CANCELLED` | Not added into the failure numerator |
| Completion | `COMPLETED / terminal runs` | Terminal runs | Non-terminal. `NO_DATA` when terminal = 0 |
| Failure | `(FAILED + TIMED_OUT) / terminal runs` | `FAILED`, `TIMED_OUT` in the numerator; all terminal runs in the denominator | `CANCELLED` is in the denominator only. `NO_DATA` when terminal = 0 |
| p50, p95, p99 | `percentile_cont` of `ended_at - started_at` in milliseconds | Terminal runs with both timestamps and `ended_at >= started_at` | Non-terminal runs, null timestamps, negative durations. `NO_DATA` when the valid sample is empty |
| Approval wait | Mean `decided_at - requested_at` for `APPROVED` | `APPROVED` with both timestamps and a non-negative wait | `PENDING`, `CANCELLED`, `REJECTED`, `EXPIRED`, null `decided_at`, negative waits. Rejected and expired means are separate fields |
| Pending approvals | Count of `PENDING` | `PENDING` | Decided approvals |
| Policy denial | `DENY / proposals with a policy decision` | Proposals on runs in this workspace | Proposals with a null decision. `NO_DATA` when there are none |
| Tokens | Sum of recorded `input_tokens + output_tokens` | Every run in the workspace | Other workspaces. Zero means the recorded sum is zero. The columns are `NOT NULL`, so the product cannot tell “unknown” from “zero” on a single run |
| Cost | Sum of stored `estimated_cost_usd`, plus `pricingStatus` | Runs in the workspace | See pricing statuses below |
| Queue depth | Count of open jobs | `PENDING`, `RETRY`, `RUNNING` with this `workspace_id` | `SUCCEEDED`, `DEAD`, and jobs whose `workspace_id` is null. Redis is not this queue |

## State classes

Defined once on `RunStateMachine`.

- Terminal: `COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT`.
- Waiting: `APPROVAL_REQUIRED`.
- Active: every other existing state (`QUEUED`, `RUNNING`, `RETRIEVING`, `THINKING`, `TOOL_PROPOSED`, `APPROVED`, `REJECTED`, `TOOL_EXECUTING`).

No new run states were added.

## Latency dimensions

1. **End-to-end duration** is what p50, p95, and p99 measure. It includes human approval wait because that wait sits inside `started_at` … `ended_at`.
2. **Approval wait** is measured from `approvals.requested_at` and `approvals.decided_at`, not by slicing the run interval.
3. **Agent execution duration** is not stored. Subtracting approval wait from the run interval would invent a processing time, so the API does not report one.

`invalidDurationCount` counts terminal runs missing a timestamp, plus any run with `ended_at < started_at`. Those rows stay out of the percentile. The endpoint still returns 200.

p95 at or above `attentionMs` (300000, five minutes) sets `latency.high`. The UI says “High latency observed” and still shows the value. Five minutes is an attention line for this end-to-end number, not a service-level objective.

## Approval populations

| Population | In the card | In the API |
|---|---|---|
| `APPROVED` with a valid wait | Yes. This is the card | `approvalWait.approved` |
| `REJECTED` with a valid wait | No | `approvalWait.rejected` |
| `EXPIRED` with a valid wait | No. The card context shows the expired average when one exists | `approvalWait.expired` |
| `PENDING` | No | Counted only as pending approvals |
| `CANCELLED` | No | Ignored by the averages |
| Missing or negative `decided_at` | No | `invalidCount` on that population |

## Cost statuses

| pricingStatus | When | What the card shows |
|---|---|---|
| `NO_USAGE` | No runs | No data |
| `NO_TOKENS` | Runs exist, recorded token sum is 0, every model has a known price | No data. Context: no tokens were recorded |
| `CONFIGURED_ZERO` | Token sum is positive and every model is `grounded-extractive-v1` | `$0.00`. Context: configured zero-price model |
| `PRICED` | Token sum is positive and a run used `gpt-4o-mini` (list price $0.15 / $0.60 per 1M tokens) and no model is unknown | The stored dollar sum |
| `PRICING_UNAVAILABLE` | Any model is outside those two | Pricing unavailable. The stored zero is not presented as a price |

`grounded-extractive-v1` is a configured zero. It is not an unknown price and it is not “no tokens”.

## Workspace and failures

The workspace id comes from the authenticated membership. `X-Workspace-Id` is accepted only when that user is a member. Every aggregate in `MetricsService` binds `:workspace`.

If Postgres is unavailable, the request fails with the existing database error. The API does not answer `queueDepth: 0` to hide that. Redis is not consulted. A Redis outage does not change queue depth.

The Prometheus gauge `aegis.queue.depth` is still process-wide. On a database error it reports NaN rather than a fake zero. It is not the Operations number.

## Display

The API keeps milliseconds. The UI formats them: under one second as `123 ms`, under one minute as `1.5s`, then `6m 26.0s`. The tooltip has the rounded exact milliseconds. Completion and failure cards include the counts that produced the rate.

## Tests that lock this contract

- 10 completed: completion 100%, failure 0%
- 5 completed and 5 failed: both 50%
- 5 completed, 3 failed, 2 timed out: both 50%
- 5 completed and 5 cancelled: completion 50%, failure 0%
- No terminal runs: completion and failure `NO_DATA`
- Pending approval excluded from the approved average
- Approved, rejected, and expired waits stay apart
- Two sample lists do not share runs, tokens, cost, or queue depth
- Negative and missing terminal durations excluded
- `CONFIGURED_ZERO` for `grounded-extractive-v1` with tokens
- `PRICING_UNAVAILABLE` for an unknown model
- SQL text for runs, approvals, jobs, policy, and evaluations filters on `workspace_id`

## Index

`jobs_workspace_open_idx` on `jobs (workspace_id)` where status is `PENDING`, `RETRY`, or `RUNNING`. The worker claim index is `(status, next_run_at)` and cannot seek by workspace. Run aggregates read the workspace’s rows through the existing `(workspace_id, created_at)` index. No extra run index was added, because the summary reads the duration and token columns rather than serving an index-only count.

## What stayed the same

The support run, approval, and ticket path is unchanged. The default model price is still zero. The dashboard is still the same twelve cards.
