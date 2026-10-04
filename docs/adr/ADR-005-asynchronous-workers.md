# ADR-005: Asynchronous workers

## Status

Accepted

## Context

Document extraction, embedding, ticket creation, and evaluation are slow or failure-prone. Doing them on the HTTP thread would freeze the UI and couple their retries to a browser request.

## Decision

The API commits a job row and returns. Workers claim jobs with a lease. The orchestrator that calls the model also runs on a bounded pool after the HTTP response is committed, so the browser gets a run id immediately.

## Consequences

The system is eventually consistent between "approved" and "ticket exists". The timeline shows that gap on purpose. Clients must tolerate it.
