# Failure Modes

| Failure | What the user sees | What the system does |
|---|---|---|
| Worker process dies mid-ticket | Run stays `TOOL_EXECUTING` until retry or timeout | Lease expires. Control plane requeues if attempts remain. The idempotency key makes the second attempt return the first ticket. |
| Worker dies before the ticket insert | Run stays `TOOL_EXECUTING` | Retry executes once. |
| Model or runtime times out | Timeline shows retry steps, then a failed run if the budget of attempts is spent | Retryable. Backoff inside the orchestrator. Run timeout still applies. |
| Redis unavailable | No cross-instance push | SSE on the handling instance still works. Other instances rely on the client's poll fallback. Jobs, approvals, and tickets are in Postgres. |
| Postgres unavailable | API readiness fails. UI shows an error state. | No approval is recorded, so no ticket is created. In-flight workers stop claiming. There is no "success" response that was not committed. |
| Ticket execution returns 500 | Retry history grows | Retryable until max attempts, then the job is dead and the run fails with `TOOL_ERROR`. |
| Ticket arguments invalid | Run completes or the tool step is denied | Non-retryable. Policy denies unknown fields and bad priorities before a job exists. |
| Approval expired | Reviewer cannot approve. Worker will not execute. | Status `EXPIRED`. |
| Duplicate job or duplicate approve | One ticket | Unique keys. |
| Prompt injection in a document | Answer may describe the document as data | No authority change. |
| Budget exceeded | Run stops with `BUDGET_EXCEEDED` | Further model and tool work is not started. |
| User cancels | Timeline shows cancelled | Worker checks run state before the insert and skips the write. |

Retry classes are listed in `aegislib.retries`. The matrix is the one the product promised: LLM timeout, HTTP 429, database unavailable, and ticket HTTP 500 are retryable. Invalid arguments, ticket HTTP 400, approval expiry, and prompt injection are not.
