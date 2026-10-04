# Final audit

Date: 2026-10-04. Status means what was executed in this workspace. The local Compose stack was started and the support workflow was exercised in the browser.

| Capability | Status | Evidence |
|---|---|---|
| Authentication | COMPLETE | Browser login at `http://localhost:3000/login` as `dev.operator@aegistrace.local` reached the dashboard. Logout returned to the login page. Reviewer login reached the approval queue. |
| RBAC | PARTIAL | Operator created a run. Reviewer saw the queue and approved it. A denied cross-role call was not captured in this pass. |
| RAG | COMPLETE | Live run `709e6edf-b505-40c3-99e4-0802d5fb5272` retrieved 4 chunks from the application and cancellation policies and showed those citations in the UI. |
| Citations | COMPLETE | The same script checks that every quote is a substring of the cited chunk. Report: `evaluation/latest-report.json`. |
| Agent lifecycle | COMPLETE | The same run's timeline moved RUNNING, RETRIEVING, THINKING, TOOL_PROPOSED, APPROVAL_REQUIRED, TOOL_EXECUTING, COMPLETED. |
| Tool registry | COMPLETE | Seeded tools are the two named in the product brief. |
| Policy engine | COMPLETE | `PolicyEngineTest` and `ArgumentValidatorTest` passed under `mvn test` (16 tests in the module, 0 failures). |
| Approval | COMPLETE | Reviewer clicked Approve on run `709e6edf-b505-40c3-99e4-0802d5fb5272`. The queue cleared and the timeline recorded the approval. |
| Idempotency | PARTIAL | Run `8fc495e2-46a2-492f-8d99-b5509d3f4ab0` received two approve calls and stored one ticket (`92f6ec44-010d-4d35-9f6d-aa8fc599277d`) and one tool execution. A crash-after-insert replay was not executed. |
| Async workers | COMPLETE | The worker consumed the execute-tool job and the UI showed ticket `7b9ab3d7-63a4-4bd1-825f-44347d87d2c2`. Seed produced 11 active documents and 13 chunks. |
| Retry | COMPLETE | `classify_retry` matches the matrix and is asserted by pytest and the evaluation script. |
| Observability | PARTIAL | OTLP, Prometheus scrape, Grafana provisioning, and JSON logs are in the repo. No trace was exported in this pass. |
| Evaluation | COMPLETE | 30 dataset cases, 0 failures, in `evaluation/latest-report.json` produced by `evaluation/run_critical.py`. |
| Security | PARTIAL | Threat model, origin check, injection tests, and `scripts/scan_secrets.py`. No dependency CVE scan was executed. |
| CI/CD | PARTIAL | `.github/workflows/ci.yml` exists and was not executed on GitHub from this machine. |
| Docker | COMPLETE | `docker compose ps` showed postgres, redis, garage, control-plane, agent-runtime, worker, frontend, Jaeger, Prometheus, Grafana, and the collector up. Host ports are 5435, 6382, and 9010 because 5432, 6379, and 9000 were already taken. |
| AWS | PARTIAL | `infrastructure/aws/main.tf` describes VPC, RDS, ElastiCache, S3, Secrets Manager, an ECS cluster, and logs. It does not create running services and was not applied. |
| Performance | MISSING | `scripts/benchmark.py` writes measurements. No result file is valid until that script runs. |
| Documentation | COMPLETE | README, ADRs, threat model, state machines, API boundary, interview notes, and this audit. |

Allowed words are only COMPLETE, PARTIAL, MISSING, and BROKEN. COMPLETE is used where a command in this workspace produced the evidence named in the row.
