# Technology Decision Record

This is the summary. Individual decisions are ADRs 001–010.

| Concern | Choice | Why |
|---|---|---|
| UI | Next.js, TypeScript, React, Tailwind | Server-rendered shell, typed client, one frontend. |
| Control plane | Java 21, Spring Boot 3.4 | Transactions, security filters, Flyway, a boring place to put authorization. |
| Agent runtime and worker | Python 3.12 | Retrieval, PDF, embeddings, and model HTTP live next to the code that tests them. |
| Database | PostgreSQL 16 + pgvector | Relational integrity and vectors in one transaction boundary. |
| Queue | Postgres jobs, Redis for fan-out | The approval and the job commit together. |
| Objects | MinIO locally, S3 in AWS | Same SDK path. |
| Traces | OpenTelemetry, Jaeger via the collector | One run id has to be followable across processes. |
| Metrics | Micrometer to Prometheus, Grafana | Pull model, no fake series. |
| Logs | JSON to stdout, CloudWatch in AWS | Correlation fields, content off by default. |
| CI | GitHub Actions | Unit tests, critical evaluations, secret scan, image build. |
| Cloud | AWS ECS on EC2 or Fargate, RDS, ElastiCache, S3 | Matches the data services. No Kubernetes until the operational need exists. |
| Offline model | Feature hashing + extractive grounding | The demo and CI run without an API key. The provider name is shown in the UI. It is not labeled as a hosted LLM. |
| Hosted model | OpenAI-compatible HTTP | Used only when `OPENAI_API_KEY` is set. |

Host JDK on the development machine may be newer than 21. The control-plane image compiles with a Java 21 Maven image so the runtime bytecode stays on the supported Spring Boot line.

## Environments

| Name | How |
|---|---|
| local | Docker Compose, seed enabled, grounded provider |
| test | JUnit and pytest, Testcontainers where a test needs Postgres |
| staging/demo | Same Compose topology, or the Terraform stack with seed enabled and a demo flag |
| production-like | Terraform, seed disabled, default secrets refused, content logging off |

## Embedding choice

`feature-hash-v1` maps tokens and bigrams into 384 signed dimensions (feature hashing). It is a real retrieval method, and it is weaker than a trained embedding model. Hybrid fusion with Postgres full-text search is there so exact policy terms still surface. When an API key is configured, a knowledge base can be pinned to `text-embedding-3-small` reduced to 384 dimensions and re-indexed. Vectors from different models are never queried together.
