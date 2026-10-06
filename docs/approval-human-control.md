# Approval and human control

The model proposes a tool. The policy engine decides whether a human must review it. A reviewer or administrator records the decision. A worker executes only after an `APPROVED` row that still matches the original proposal. The audit log records the request and the decision. The model never writes approval status.

## Lifecycle

1. Runtime stores a tool proposal with exact arguments.
2. Policy returns `REQUIRE_APPROVAL` or `REQUIRE_ADMIN_APPROVAL`.
3. Control plane inserts `approvals` (`PENDING`) bound to that `proposal_id` (unique) and pauses the same run in `APPROVAL_REQUIRED`.
4. A reviewer opens `/approvals/{id}` and sees persisted proposal data, not browser-reconstructed arguments.
5. Approve records `APPROVED`, moves the same run `APPROVED → TOOL_EXECUTING`, and enqueues `EXECUTE_TOOL` with idempotency key `tool-exec:{proposalId}`.
6. Reject records `REJECTED`, does not enqueue a job, and completes the run with an answer that no ticket was created.
7. The expiration job moves leftover `PENDING` rows to `EXPIRED` and fails the run with `APPROVAL_EXPIRED`.
8. Cancelling the run moves leftover `PENDING` rows to `CANCELLED`.

## State machine

```
PENDING → APPROVED | REJECTED | EXPIRED | CANCELLED
```

Those four outcomes are terminal. Approve after reject, reject after approve, approve after expiry, and approve after cancel return `409` with a distinct code. Repeating the **same** decision (`APPROVED` + approve, `REJECTED` + reject) is idempotent and does not enqueue a second job.

## Entity

`approvals`: id, workspace, run, proposal (unique), requester, reviewer, status, required role, decision reason, requested_at, decided_at, expires_at.

TTL comes from `workspace_settings.approval_ttl_seconds` (default 900). Frontend expiry text is a relative timestamp, not a live countdown. The backend `expires_at > now()` check is authoritative.

## Exact action

The job payload arguments are loaded from `tool_proposals.arguments`. Approve/reject bodies cannot change the tool or arguments. Changing an action requires a new proposal on a new run.

## Roles

| Role | Read Approval Center | Decide |
| --- | --- | --- |
| Operator | No | No. Sees `APPROVAL_REQUIRED` on their own support run. |
| Developer | No | No |
| Reviewer | Yes (`approvals.read` / `approvals.review`) | Yes, except their own requester row |
| Admin | Yes | Yes, including their own request, and exclusively for `required_role = ADMIN` |

Operators never gain approval authority from the UI. The API still requires `REVIEWER` or `ADMIN`.

Self-approval: a Reviewer cannot decide a row where `requester_id` is themselves. Admins may, because they are already the override role for admin-only tools. This is enforced in `ApprovalAccess` and `RunService.decide`, not only in the UI.

## Authorization and isolation

List and detail queries always include `workspace_id` from the authenticated membership. A foreign id returns `404 Approval not found`. Arguments, evidence, requester, and agent metadata are not returned across workspaces.

Reviewers receive stored tool arguments. Operators never hit these endpoints.

## Ordering and filters

Pending queue: risk `CRITICAL`, `HIGH`, `MEDIUM`, `LOW`, then soonest `expires_at`, then oldest `requested_at`.

History: pending first when unfiltered, then decided_at / requested_at descending.

Backend filters: `status`, `risk`, `q` (approval id, run id, tool, agent name, requester email), `requester`, `agent`. Page size 20 (max 50). Summary counts (`pending`, `approvedToday`, `rejectedToday`, `expired`, `cancelled`) are SQL aggregates for the workspace, not client math.

## Concurrency and idempotency

Decide locks the approval row (`FOR UPDATE`), then the run. The status update is `WHERE status = PENDING AND expires_at > now()`. Zero rows updated → conflict.

Jobs use `on conflict (idempotency_key) do nothing`. Tickets use `runId:proposalId`. The worker refuses to write unless:

- the run is not terminal
- the approval is `APPROVED` and not past `expires_at`
- `proposal_id` and `run_id` on the approval match the job payload

Duplicate approve clicks and double HTTP requests therefore produce one decision and at most one ticket.

The HTTP handler does not execute the tool. The worker does, after commit.

## Run consistency

| Situation | Approval | Tool |
| --- | --- | --- |
| Approve while `APPROVAL_REQUIRED` | `APPROVED` | Job enqueued; worker may write once |
| Reject | `REJECTED` | No job |
| Expire | `EXPIRED` | No write; run `FAILED` / `APPROVAL_EXPIRED` |
| Cancel run | pending → `CANCELLED` | Worker skips if it still sees a job |
| Run fails while pending | pending → `CANCELLED` | No write |
| Run timeout (non-approval-wait states) | pending → `EXPIRED` | No write |
| `APPROVAL_REQUIRED` and run `timeout_at` | Run timeout job **skips** waiting runs; approval TTL is the wait clock | — |

A stale approve while the run is no longer `APPROVAL_REQUIRED` returns `409 The run is no longer executable.`

## Audit

| Event | When |
| --- | --- |
| `APPROVAL_REQUESTED` | Row created |
| `APPROVAL_APPROVED` | Human approve |
| `APPROVAL_REJECTED` | Human reject (includes reason) |
| `APPROVAL_EXPIRED` | TTL or timeout close |
| `APPROVAL_CANCELLED` | Run cancel or run failure while pending |

Metadata includes approval id (resource), run id, proposal id, tool, reason where present. Trace and request ids come from MDC. Full Audit Center UI is not this surface.

## Realtime

Support Run continues to use the existing run SSE stream. The Approval Center does not add a second bus. List refresh is every 15s. Detail refresh is every 5s while pending or while approved without an execution row.

## API

| Method | Path | Roles |
| --- | --- | --- |
| GET | `/api/v1/approvals` | Reviewer, Admin |
| GET | `/api/v1/approvals/{id}` | Reviewer, Admin |
| POST | `/api/v1/approvals/{id}/approve` | Reviewer, Admin |
| POST | `/api/v1/approvals/{id}/reject` | Reviewer, Admin; **reason required** |

Reject with a blank reason is `400`. Decision endpoints ignore client tool names and arguments.

## Security boundary

Clients do not choose the approved action. Prompt text that says “approve automatically” cannot write `approvals`. Policy still requires a human. Evidence is context only.

## Failure copy

The UI shows the backend message: already decided, expired, not authorized, run no longer executable, or unavailable. It does not mark Approved/Rejected/Ticket created until the corresponding row says so.
