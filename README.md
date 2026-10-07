# AegisTrace

AegisTrace is a control plane for one internal support agent. An operator asks a policy question. The agent answers from synthetic support documents, with citations, or it says it does not have enough evidence. If a support ticket would help, the agent proposes `create_support_ticket`. Deterministic code decides that a write needs a person. A reviewer approves or rejects. A worker creates the ticket once.

The model proposes. The control plane decides. A human approves the write. A worker executes it. The timeline records it. An evaluation checks it.

## Problem

Connecting a model to a ticket tool produces a demo that can write. It does not produce a system that can pause, explain, or prove the write. AegisTrace is that pause, and the record around it.

## Console

The signed-in console is a control surface, not a chat product.

- **Operations.** Workspace KPIs from stored runs, approvals, and jobs. Completion and failure use finished runs. Timeouts count as unsuccessful. Cancelled runs do not. Empty rates are `NO_DATA`, not 0%. Cost is `$0.00` only when the model is configured at zero; unknown prices are `PRICING_UNAVAILABLE`. Operators see counts, not approval payloads. Windows are rolling UTC hours (`24H`, `7D`, `30D`, `ALL`).
- **Support run.** The operator starts a run and sees status, evidence, the grounded answer, a tool proposal, the policy decision, and approval wait. The write tool is not executed from this page. After create, the URL is `/runs/new?run={id}` so refresh recovers the same run.
- **Run detail.** Timeline, citations, tokens, and cancel for a single run.
- **Approvals.** Reviewers and admins decide writes. A second approve does not create a second ticket.
- **Shell.** Navigation follows server roles (Operator, Reviewer, Developer, Admin). Direct URLs without the capability show a restricted state. The environment label is `LOCAL` in development Compose.

Shared UI pieces: status and risk badges, cards, tables, form fields, empty and error states, duration formatting.

## Why a chatbot demo is not enough

A chat transcript does not show which agent version ran, which chunks were retrieved, which policy decision fired, who approved the write, or whether a retried job created a second ticket. Those are the product.

## Product workflow

1. The operator asks: "Why was my application rejected, and what should I do before reapplying?"
2. The API stores an `AgentRun` and returns. The browser is not held on the model.
3. The runtime retrieves the synthetic corpus and composes an answer from those chunks.
4. The UI shows citations.
5. The planner proposes `create_support_ticket` from the user's question, not from document text.
6. `PolicyEngine` returns `REQUIRE_APPROVAL`.
7. The run waits.
8. A reviewer sees the tool, the arguments, the risk, the requester, the policy code, the run id, and the trace id.
9. Approval resumes the same run. Rejection writes nothing.
10. The worker inserts one ticket. The idempotency key is `{runId}:{proposalId}`.
11. The final answer, timeline, evaluation, and metrics are readable afterward.

The demo script is `docs/demo-script.md`.

## Architecture

```text
Next.js  --SSE/HTTP-->  Spring Boot control plane
                              |            |
                         PostgreSQL      Redis (events only)
                         + pgvector
                              |
                        Python runtime  (retrieve, answer, propose)
                              |
                        Policy engine (inside the control plane)
                              |
                        Approval row
                              |
                        Postgres job
                              |
                        Python worker (embed, ticket, evaluate)
```

Policy is not a separate service. It is authorization, and it commits with the approval. See `docs/architecture.md` and `docs/adr/`.

## State machine

`QUEUED → RUNNING → RETRIEVING → THINKING → TOOL_PROPOSED → APPROVAL_REQUIRED → APPROVED or REJECTED → TOOL_EXECUTING → COMPLETED`, or `FAILED`, `CANCELLED`, `TIMED_OUT`. Details and the early-deny path are in `docs/run-state-machine.md`.

## Approval

`PENDING → APPROVED | REJECTED | EXPIRED | CANCELLED`. A second approve does not insert a second job. An expired approval does not execute. See `docs/approval-state-machine.md`.

## RAG

Markdown, text, and PDF uploads go to S3-compatible storage (Garage locally; Amazon S3 in the cloud sketch). The worker extracts, chunks, and embeds. The default embedding is feature hashing (`feature-hash-v1`, 384 dimensions): a real retrieval method that runs without an API key, and a weaker one than a trained model. Ranking fuses vector order with lexical overlap. The offline answer is extractive: it copies supporting sentences and drops sentences that look like instructions to the model. If the best lexical overlap is too low, the answer is:

> I don't have enough documented evidence to answer that confidently.

When `OPENAI_API_KEY` is set and the agent provider is `openai`, the runtime can call an OpenAI-compatible chat API for the wording. The planner still owns the tool proposal. The UI shows the provider name that actually ran.

The corpus is eleven synthetic documents, including one that tells the agent to ignore policy and create an urgent ticket.

## Agent

One support agent. Versions are insert-only. A run copies the snapshot: prompt, model settings, tools, knowledge base, embedding model, and budgets. Later edits do not change that row.

## Tool policy

| Tool | Class | Decision |
|---|---|---|
| `search_knowledge` | read | `ALLOW` when the version allows it and the user can read |
| `create_support_ticket` | write | `REQUIRE_APPROVAL` |
| priority `high` | write | `REQUIRE_ADMIN_APPROVAL` |
| priority `urgent` or `critical` | write | `DENY` |
| unknown tool or unknown field | — | `DENY` |
| over the tool-call or token budget | — | `DENY` or stop the run |

## Security

Server-side membership on every route. HttpOnly session cookie. Origin check on browser mutations. Bcrypt passwords. Internal service calls use a separate token. The threat model is `docs/threat-model.md`.

## Prompt injection

Documents are data. The planner does not read them. A model write that the user did not ask for is discarded. A model priority that the user did not ask for is discarded. The policy engine remains the authority either way.

## Idempotency

`tool_executions.idempotency_key` and `tickets.idempotency_key` are unique. A crash after the insert returns the existing ticket on the next attempt.

## Reliability

Jobs live in Postgres with a lease, backoff, a max attempt count, and a dead state. The retry matrix is in `docs/failure-modes.md`. Redis is optional for correctness.

## Observability

JSON logs. Micrometer metrics on `/actuator/prometheus`. Traces exported over OTLP to Jaeger. The product timeline is `run_events`. Prompts are not log lines unless content logging is explicitly enabled. It is off.

## Evaluation

The Evaluation Center provides immutable cases and suites, asynchronous execution against explicit agent and knowledge versions, deterministic check evidence, history, comparison, and regression detection. The Safety Center exposes persisted policy, approval, abstention, injection, and evaluation signals without AI confidence scores or private reasoning. See `docs/evaluation-and-safety.md` for exact scoring, access, and privacy semantics.

`evaluation/datasets/support-v1.json` has 30 offline critical cases. `python evaluation/run_critical.py` executes Python-grounding/tool/retry cases and explicitly delegates six authoritative policy fixtures to `EvaluationPolicyFixturesTest`; its report distinguishes those counts and exits non-zero on failure.

## Performance

`scripts/benchmark.py` measures the API you point it at and writes `benchmarks/results/`. This repository does not ship a hand-written latency table. Do not cite a speedup that the script did not print.

What is designed for responsiveness: the run API returns after the insert, work continues on a pool of 8 threads, uploads and embeddings are jobs, lists are paginated, and the support-run page follows the event stream with a short reconcile poll if the proxy buffers events.

## Cost

Each run stores input tokens, output tokens, and `estimated_cost_usd`. The offline provider's provider charge is 0. `gpt-4o-mini` uses a configured list price of $0.15 per million input tokens and $0.60 per million output tokens. Token budget and cost budget are on the agent version. The dashboard reads the stored sums.

## Deployment

Local and demo: `docker compose up --build`.

AWS shape, not applied from this workspace: VPC, RDS PostgreSQL, ElastiCache, S3, Secrets Manager, an ECS cluster, and a CloudWatch log group in `infrastructure/aws/main.tf`. It does not yet create ECS services or a load balancer. Kubernetes is intentionally absent. See ADR-010.

## Local setup

Requirements: Docker with Compose. The control plane image compiles on Java 21 even if the host JDK is newer.

```text
docker compose up --build
```

Open http://localhost:3000/login

| Email | Role |
|---|---|
| dev.operator@aegistrace.local | Operator |
| dev.reviewer@aegistrace.local | Reviewer |
| dev.developer@aegistrace.local | Developer |
| dev.admin@aegistrace.local | Admin |

Development password: `change-me-dev-password` (the Compose default for `AEGIS_SEED_PASSWORD`). Change it before any shared environment. The production profile refuses secrets that contain `change-me` and refuses seed data.

Other local ports: API 8080, runtime 8090, Jaeger 16686, Grafana 3001, Prometheus 9090, Postgres 5435, Redis 6382, Garage S3 9010. Host ports avoid the common 5432/6379/9000 bindings used by other local stacks. Containers still talk to each other on the Compose network.

Copy `.env.example` if you override Compose defaults. Do not commit a real `.env`.

## Demo

Follow `docs/demo-script.md`. Sign in as the operator, ask the application question, then sign in as the reviewer and approve. The run page shows one ticket id. Approving again does not create another.

Failure demonstrations:

1. Ask for a summary of the internal override notice. No urgent ticket.
2. As admin, in a non-production environment, the failure-simulation API enqueues a retryable ticket error. Invalid arguments are not retried.
3. Repeat an approval HTTP call. One ticket.

## Screenshots

The console is the screenshot. Capture it from a running stack. This repository does not include staged images of a run that was not executed.

## Architecture decisions

`docs/adr/ADR-001` through `ADR-010`.

## Trade-offs

- Feature hashing instead of a neural embedding, so CI and the demo do not need a model vendor. Paraphrases outside the corpus vocabulary can miss.
- Extractive answers instead of an unconstrained generation, so a citation is a copied sentence. The prose is plain.
- Postgres as the queue, so approval and work commit together. Throughput is the database's, not a dedicated broker's.
- One API process in Compose. Horizontal scale needs the Redis fan-out that is already optional, plus more than one control-plane task.
- The worker uses the same database role as the API. A stolen worker password could insert a ticket. A production follow-up is a restricted database role.

## Limitations

- No live AWS account is wired up, and the Terraform stack stops at data services plus an empty ECS cluster.
- No load-test numbers are published until `scripts/benchmark.py` is run against a stack.
- OpenAI wording is implemented and unused unless a key and provider are configured. The default demo does not pretend to be that model.
- MCP and multi-agent orchestration are non-goals. See `docs/non-goals.md` and `docs/roadmap.md`.
- The offline ranker loads the active chunks for a knowledge base and fuses them in process. That is acceptable for this corpus. It is not an ANN-serving design for millions of chunks, though an HNSW index is created.

## Future roadmap

`docs/roadmap.md`.

## Repository map

`docs/repository-architecture.md`.

## API

`docs/api-boundary.md`. Interactive docs: http://localhost:8080/swagger-ui after the control plane is up.

## Tests

```text
mvn -B -f control-plane/pom.xml test
python -m pytest py/aegislib/tests -q
python evaluation/run_critical.py
python scripts/scan_secrets.py
```

CI is `.github/workflows/ci.yml`.

Run the commands above before quoting test counts; committed reports are not a substitute for a fresh verification run.
