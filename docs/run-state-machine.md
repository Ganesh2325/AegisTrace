# Run State Machine

```text
QUEUED
  -> RUNNING
    -> COMPLETED                 (read tool denied before retrieval)
    -> RETRIEVING
      -> THINKING
        -> COMPLETED                 (answer only, no write proposed)
        -> TOOL_PROPOSED
            -> COMPLETED             (policy DENY, nothing executed)
            -> TOOL_EXECUTING        (READ_ONLY allow, not used for writes)
            -> APPROVAL_REQUIRED
                -> REJECTED
                    -> COMPLETED     (no ticket)
                -> APPROVED
                    -> TOOL_EXECUTING
                        -> COMPLETED
```

From any non-terminal state the run may move to `FAILED`, `CANCELLED`, or `TIMED_OUT`.

Terminal states: `COMPLETED`, `FAILED`, `CANCELLED`, `TIMED_OUT`.

`REJECTED` and `APPROVED` are non-terminal run states. `REJECTED` means the reviewer declined the proposal. It must reach `COMPLETED` so the operator still receives the grounded answer. It must not enter `TOOL_EXECUTING`.

## Who may transition

| Transition | Actor |
|---|---|
| QUEUED -> RUNNING | Control plane orchestrator |
| RUNNING -> RETRIEVING -> THINKING | Control plane, after the runtime returns |
| THINKING -> TOOL_PROPOSED | Control plane, only if the runtime returned a proposal |
| TOOL_PROPOSED -> APPROVAL_REQUIRED | Policy engine result |
| APPROVAL_REQUIRED -> APPROVED or REJECTED | Reviewer or admin HTTP action |
| APPROVED -> TOOL_EXECUTING | Job enqueue |
| TOOL_EXECUTING -> COMPLETED | Worker callback |
| * -> CANCELLED | Operator who owns the run, or admin |
| non-approval states -> TIMED_OUT | Scheduler comparing `timeout_at`. `APPROVAL_REQUIRED` waits on approval expiry instead, so a short run timeout does not cancel a reviewer. |
| * -> FAILED | Orchestrator or worker after retries are exhausted, or a non-retryable error |

The transition table is `RunStateMachine` in the control plane. A repository update that skips the table is a defect. Persisted state and the in-memory table are the same enum.

## Crash, timeout, and duplicate answers

- Worker crash after the ticket insert: the idempotency key already exists. The reaper requeues the job. The worker returns the stored result and callbacks again. The callback is ignored if the run is already terminal.
- Model or runtime timeout: the orchestrator retries with backoff while attempts remain and the run timeout has not passed. Then the run becomes `FAILED` with category `MODEL_TIMEOUT` or `TIMED_OUT`.
- Approval expiry: the scheduler sets the approval to `EXPIRED` and the run to `FAILED` with category `APPROVAL_EXPIRED`. The worker will not execute an expired approval even if a job was already queued.
- Duplicate approval HTTP call: the approval row is updated only from `PENDING`. The second call returns the existing decision and does not insert another job.

## Failure categories

`MODEL_TIMEOUT`, `RETRIEVAL_ERROR`, `POLICY_DENIED`, `APPROVAL_EXPIRED`, `APPROVAL_REJECTED` (informational, run still completes), `TOOL_ERROR`, `BUDGET_EXCEEDED`, `CANCELLED`, `TIMED_OUT`, `DEPENDENCY_UNAVAILABLE`, `INTERNAL`.

`POLICY_DENIED` does not fail the run. The denial is a step on a completed answer. The category is recorded on the step, not as a failed run, because the operator still received the grounded response.
