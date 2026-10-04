# CV bullets

These describe what the repository actually contains. They do not include latency, accuracy, or cost improvements, because those were not measured as a before-and-after experiment.

- Built a support-agent control plane in Spring Boot with a Python runtime and worker, PostgreSQL and pgvector, Redis for live updates, and Docker Compose, including immutable agent versions, a deterministic tool policy, and a Postgres job queue.
- Implemented citation-grounded answers over a synthetic policy corpus, human approval before ticket creation, idempotent writes keyed by run and proposal, and checks that a retrieved document cannot change tool authority.
- Added OpenTelemetry traces tagged by run id, Prometheus metrics, structured logs without prompt text, retry classification, and a 30-case evaluation script. In this workspace that script reported 0 failures, and the Java policy, argument, state-machine, and cost tests reported 16 passing tests.

Replace the last sentence if a later run of `evaluation/run_critical.py` or `mvn test` produces a different result. Do not invent a replacement number.
