# Approval State Machine

```text
PENDING -> APPROVED
PENDING -> REJECTED
PENDING -> EXPIRED
PENDING -> CANCELLED
```

Those four outcomes are terminal. An approval cannot be reopened.

## Creation

An approval row is created only after the policy engine returns `REQUIRE_APPROVAL` or `REQUIRE_ADMIN_APPROVAL`. It stores the requester, the tool name, the exact argument JSON, the policy decision, the run id, the proposal id, and `expires_at` (15 minutes from creation by default).

`REQUIRE_APPROVAL` may be decided by a reviewer or an admin. `REQUIRE_ADMIN_APPROVAL` may be decided only by an admin.

## Reject

The approval becomes `REJECTED`. No job is enqueued. No ticket row is inserted. The run moves `REJECTED -> COMPLETED` and the final answer states that no ticket was created.

## Approve

The approval becomes `APPROVED` in the same database transaction that inserts one job. The job idempotency key is `tool-exec:{proposalId}`. A second approve request finds the approval is no longer `PENDING` and inserts nothing.

The worker resumes the same run id. It does not create a new run.

## Expire

A scheduler moves `PENDING` approvals with `expires_at < now()` to `EXPIRED`. Execute paths check status and expiry again. Expired approvals do not create tickets.

## Cancel

Cancelling the run cancels a still-pending approval. An already approved execution that has not finished checks the run state and skips the write if the run is `CANCELLED`.

## Audit

Every decision records actor, timestamp, tool, arguments, policy decision, trace id, and run id. Execution result is audited when the worker callbacks.
