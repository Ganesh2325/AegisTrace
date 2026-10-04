# Phase 0 Audit

Date: 2026-10-04

## Repository at start

`D:\newProject` contained no files. There was no existing implementation to preserve and no conflict with an earlier architecture.

## Environment

| Tool | Found |
|---|---|
| Java | 26.0.2 on the host. Control plane targets 21 inside the build image because Spring Boot 3.4 is not validated on 26. |
| Maven | Not on the PATH. CI and the Docker build download or include Maven. |
| Python | 3.14.7 on the host. Service images use 3.12. |
| Node | 22.20.0 |
| Docker | 29.2.1 |

## Missing dependencies

Everything. Postgres, Redis, MinIO, and an optional OpenAI-compatible key. The offline provider exists so the absence of a key is not a fake success.

## Conflicts

None. The host JDK being newer than the target JDK is a build constraint, not a product conflict. It is handled by compiling in the Java 21 image.

## Gate

Design documents written before production code:

- Product brief, architecture, data model, run state machine, approval state machine
- Threat model, MVP features, non-goals, demo script
- Technology decision record, repository layout, API boundary
- ADR-001 through ADR-010
- Retention, failure modes, roadmap

Phase 0 exit: PASS as a design lock. Implementation starts at Phase 1 against these documents.
