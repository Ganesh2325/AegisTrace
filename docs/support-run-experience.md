# Support Run experience

`/runs/new` is the operator execution workspace. It is not a chatbot transcript. `/runs/{id}` remains the compact run-detail page.

## UX architecture

Idle: question form on the left, agent context on the right.

After `POST /api/v1/runs` returns, the URL becomes `/runs/new?run={id}` so refresh reloads that run and does not create another. Main column: question, answer, evidence, tool proposal, approval wait, outcome. Secondary column: current state, identifiers, timeline. Below `lg`, status is first.

## Run lifecycle

Backend states are `RunStateMachine`: `QUEUED`, `RUNNING`, `RETRIEVING`, `THINKING`, `TOOL_PROPOSED`, `APPROVAL_REQUIRED`, `APPROVED`, `REJECTED`, `TOOL_EXECUTING`, plus terminal `COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT`.

The UI does not keep a parallel state machine. It renders `run.state` from `GET /api/v1/runs/{id}/execution`. A delayed `RUNNING` event cannot replace a terminal GET.

## State mapping

| Backend | Presentation |
|---|---|
| `QUEUED` / `RUNNING` | Run created / running |
| `RETRIEVING` | Retrieving knowledge |
| `THINKING` | Model processing (no private reasoning) |
| `TOOL_PROPOSED` | Action proposed |
| `APPROVAL_REQUIRED` | Waiting for reviewer |
| `TOOL_EXECUTING` | Tool executing after approval |
| `COMPLETED` | Final answer |
| `FAILED` | Failure category + safe message |
| `CANCELLED` | Cancelled after backend confirm |
| `TIMED_OUT` | Timed out + duration |

## API dependencies

- `GET /api/v1/agents` — idle agent context only. One active agent: no dropdown.
- `POST /api/v1/runs` — returns the execution payload (202). Rate limit + per-user advisory lock exist. There is no client idempotency key. The UI disables submit while the request is in flight.
- `GET /api/v1/runs/{id}/execution` — one payload: run (immutable `agentVersionId` / `agentVersionNumber` / `agentName`), events, proposal, approval summary, capabilities. Snapshot / system prompt are omitted.
- `GET /api/v1/runs/{id}/events` — existing SSE.
- `POST /api/v1/runs/{id}/cancel` — Operator (own run) or Admin, and only if `CANCELLED` is a legal transition.

`GET /api/v1/runs/{id}` is unchanged for the detail page.

## Realtime behavior

SSE named events trigger a single-flight GET of the execution payload. Event `sequence` deduplicates the timeline. A 3s reconcile poll continues while the run is non-terminal because the Next.js `/backend` rewrite can buffer SSE. If the EventSource is not `OPEN`, the panel shows **Live updates unavailable** with last-updated seconds. The UI does not claim LIVE.

Navigating away does not cancel the run.

## Evidence display

Citations from the run row: title, section, page, score, chunk id, quote. Knowledge has no document inspector, so there is no View source link.

## Tool proposal display

Tool name, classification, risk, policy decision/code come from the stored proposal. Arguments and model `reason` are included only for Reviewer, Admin, and Developer. Operators see that a write was proposed and that policy required approval, without argument payloads.

There is no Execute control on this page.

## Authorization

| Role | Create | Own execution | Other operator run | Proposal arguments | Approvals link |
|---|---|---|---|---|---|
| Operator | Yes | Yes | 403 | No | No |
| Reviewer | No | Runs with an approval | 403 without approval | Yes | Yes |
| Developer | No | Yes in workspace | Yes | Yes | No |
| Admin | Yes | Yes | Yes | Yes | Yes |

Wrong workspace id → 404. Frontend `runs.create` still gates `/runs/new`.

## Cancellation

Shown only when `capabilities.canCancel` is true. The badge does not flip to Cancelled unless the cancel response (or a later GET) says so. 409 stays on the current state.

## Errors

Create failure keeps the question. 401 shows session expired and clears the payload. 403 uses ForbiddenState. SSE failure keeps the last good payload.

## Responsive / accessibility

Two columns from `lg`. Question field is labelled. Status is text via StatusBadge. Timeline is an ordered list. Copy run ID is a real button.

## Performance

Acknowledgement is the 202 create body (run id + `QUEUED`/`RUNNING`). Later stages arrive through SSE-triggered GET. No request waits for the model on the UI thread.
