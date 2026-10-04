# MVP Feature List

In scope and required for the workflow to be real:

- Local login, bcrypt passwords, HttpOnly session cookie, logout audit.
- Server-side roles: developer, operator, reviewer, admin.
- Workspace membership on every query.
- Agent, immutable prompt version, immutable agent version, activate and deactivate.
- Tool registry with exactly two tools.
- Deterministic policy: `ALLOW`, `DENY`, `REQUIRE_APPROVAL`, `REQUIRE_ADMIN_APPROVAL`.
- Knowledge upload (markdown, text, PDF), object storage, chunking, embeddings, pgvector, hybrid rank fusion.
- Synthetic corpus including one prompt-injection document.
- Abstention when evidence is weak.
- Citations: document, chunk, section, page, score.
- Agent run lifecycle and visible timeline.
- Cancellation and timeout.
- Approval queue, approve, reject, expiry.
- Idempotent ticket creation.
- Postgres-backed jobs, leases, retries, dead letter.
- Audit events for login, logout, configuration, roles, approvals, policy, tool execution.
- OpenTelemetry traces, Prometheus metrics, JSON logs, Grafana and Jaeger in Compose.
- Evaluation dataset of at least 30 scenarios and critical checks that can fail CI.
- Security tests for the five mandatory attacks.
- Docker Compose as the local and demo environment.
- AWS architecture as Terraform for ECS, RDS, ElastiCache, S3, Secrets Manager, and CloudWatch. Live apply requires an account; the repository does not claim an account it does not have.
- Next.js console: dashboard, support run, run detail, approvals, agents, knowledge, evaluation, observability, audit, admin, and the recruiter page.

Explicitly not features: extra tools, multi-agent orchestration, MCP, Kubernetes, fake metric cards.
