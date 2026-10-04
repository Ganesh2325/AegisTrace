# Repository Architecture

```text
frontend/            Next.js console
control-plane/       Spring Boot API, policy, approvals, audit
agent-runtime/       FastAPI retrieval and answer service
worker/              Job worker
py/aegislib/         Shared retrieval, grounding, planner, job execution
knowledge/seed/      Synthetic corpus loaded by the worker
evaluation/          Dataset and critical-check runner
infrastructure/      Compose support, Prometheus, Grafana, collector, Terraform
docs/                Design lock, ADRs, threat model, interview notes
benchmarks/          Scripts. Results are generated, not authored.
scripts/             Smoke, secret scan, load
docker-compose.yml
```

Dependency direction:

- `frontend` calls only the control plane.
- `control-plane` calls the agent runtime over HTTP. It does not import Python.
- `agent-runtime` and `worker` import `aegislib`. They do not import the control plane.
- `aegislib` does not import FastAPI or Spring.
- Tests for policy live in Java. Tests for grounding, injection, and retry classification live in Python.

The tool gateway and the policy engine are packages inside the control plane (`com.aegistrace.policy`, `com.aegistrace.tool`). A separate network hop would not create a stronger trust boundary in the MVP, because both would share the same database credentials and the same release. Splitting them is justified only when a different team needs to ship policy on a different cadence.
