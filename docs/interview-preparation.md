# Interview preparation

Answers describe this repository, not a generic design.

## System design

**Why this architecture?** Authorization has to commit with the approval and the job. That work sits in Spring, in one database transaction. Retrieval and PDF parsing sit in Python because that is where the model client and the tests for grounding live. The UI never waits on the model. The HTTP call returns when the run row commits.

**Why Spring Boot?** The control plane is filters, transactions, migrations, and a policy function. Spring is a boring place to put that. See ADR-001.

**Why Python?** The runtime plans. The worker embeds, executes the approved ticket, and scores the run. See ADR-002.

**Why PostgreSQL?** Idempotency keys, foreign keys, and the job lease are the same database as the approval. A lost Redis node cannot lose a ticket decision.

**Why pgvector?** Eleven synthetic documents do not justify a second database. Vectors live next to the chunk text. The default embedder is feature hashing into 384 dimensions, named `feature-hash-v1`. It is deterministic and offline. It is weaker than a neural embedding. Hybrid rank fusion with lexical overlap is what makes the demo questions hit the right policy.

**Why Redis?** Fan-out for server-sent events when more than one API instance is running. It is not the queue. Readiness stays up when Redis is down.

**Why async workers?** Extraction, embedding, ticket creation, and evaluation are slow and retryable. The browser gets a run id and a timeline.

**Why SSE?** The timeline is a stream of persisted events. The client also polls, because a dropped socket must not be the only way to see a completed run.

**Why OpenTelemetry?** A run crosses the API, the runtime, and the worker. Spans are tagged with `run.id`. The approval wait is not one span held open for minutes, because a process restart would erase it. The timeline in Postgres is the durable story. Jaeger is for latency inside one activation.

## Distributed systems

**Worker crash.** The job lease expires. The control plane marks the job retryable if attempts remain. If the ticket row was inserted, the unique idempotency key `{runId}:{proposalId}` makes the next attempt return that ticket.

**Duplicate events.** Approving twice updates the approval only from `PENDING`. The job insert uses `ON CONFLICT (idempotency_key) DO NOTHING`. The ticket insert does the same.

**Idempotency.** The key is the run id plus the proposal id. The database constraint is the guarantee. The code also checks for an existing successful execution.

**Retries.** LLM timeout, HTTP 429, database unavailable, and ticket HTTP 500 retry with exponential backoff, capped at 60 seconds, up to the job's max attempts. Invalid arguments, HTTP 400, approval expiry, and prompt injection do not retry.

**Eventual consistency.** After approval, the run is `TOOL_EXECUTING` until the worker callbacks. The ticket does not exist in that gap. The timeline shows it.

**Redis down.** Jobs, approvals, and tickets are unaffected. Live push may only reach the API instance that handled the request. The UI polls the run.

## AI

**Why RAG?** The answer has to be checkable against the support corpus. Fluency is not evidence.

**Why not fine-tuning?** The corpus is small, synthetic, and expected to change by uploading a document. Tuning would not add the approval boundary.

**How do you evaluate RAG?** `evaluation/run_critical.py` loads the seed corpus, ranks it with the same functions as the runtime, and checks citations, abstention, and tool choice. A per-run heuristic checks that each quote is a substring of its chunk. That is not a human labeling study.

**How do you detect hallucination?** The offline composer copies sentences out of retrieved chunks. If nothing overlaps the question enough, it abstains with a fixed sentence. The per-run score is 0 when a non-abstaining answer has a quote that is not in the chunk.

**How do you prevent prompt injection?** Retrieved text is not an input to the tool planner. The planner reads the user question. A model-suggested write is dropped when the user did not ask for a write. A model-suggested priority is ignored when it is not in the user question. The policy engine still runs on whatever proposal remains. Documents cannot change the engine.

**How do you control autonomy?** Two tools. The read is invoked by the control plane as the retrieval step. The write cannot run on the request thread. Max tool calls default to 3. A token budget can stop a run before the runtime is called.

**How do you control token cost?** The snapshot stores `tokenBudget` and `costBudgetUsd`. The offline provider records a chars/4 estimate and a zero provider charge. `gpt-4o-mini` uses a configured list price of $0.15 / $0.60 per million tokens. That number is an estimate, not an invoice.

## Security

**Why deterministic policy?** A model can be persuaded. `PolicyEngine` cannot. Unknown tools, bad arguments, missing permission, call limits, budgets, and prohibited priorities are code.

**Why human approval?** `create_support_ticket` writes a row a person will act on. The reviewer sees the tool, the arguments, the risk, the requester, and the policy code.

**How does RBAC work?** The session cookie carries a user id, not a role. Every request loads membership from Postgres. Operators read only their runs. Reviewers read runs that have an approval. Developers and admins read the workspace. The UI hiding a button is not the control.

**How do you protect tools?** An allowlist on the agent version, a registry of two tools, schema validation, and an approval row before the worker inserts a ticket.

**How do you prevent privilege escalation?** A reviewer cannot approve a decision that required `ADMIN`. A high priority is `REQUIRE_ADMIN_APPROVAL`. Urgent and critical are `DENY`. Extra JSON fields are `DENY`.

## Observability

**How do you trace a run?** Take the run id from the UI. Read `run_events`. In Jaeger, search the attribute `run.id`. Logs include `run_id`, `trace_id`, `workspace_id`, and `user_id` when they exist.

**What metrics matter?** Run count, completion, failure, latency percentiles, retrieval and model time, tokens, estimated cost, approval wait, policy denials, queue depth, retries, evaluation pass rate. The dashboard query is `GET /api/v1/metrics/summary`.

**How do you debug latency?** The timeline timestamps say which step waited. The model and retrieval timers say whether the wait was the runtime. Approval wait is a separate number because a human is in it.

**What should not be logged?** Prompts, model text, retrieved passages, tool arguments, and tool output. Content logging defaults off. The product UI still shows the operator their own question, and that text is redacted after the retention window.

## Reliability

**How does retry work?** See the matrix above. Attempt rows are stored in `job_attempts`. Exhausted jobs become `DEAD` and are visible to an admin.

**How do you prevent duplicate tickets?** Unique `tickets.idempotency_key` and unique `tool_executions.idempotency_key`.

**What happens when approval expires?** A scheduler sets the approval to `EXPIRED` and fails the run if it is still waiting. The approve call does the same if the deadline passed. The worker refuses an approval that is not `APPROVED` or whose `expires_at` has passed.
