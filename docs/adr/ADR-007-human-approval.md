# ADR-007: Human approval

## Status

Accepted

## Context

`create_support_ticket` writes a durable support record. An operator asking a question has not, by that action alone, authorized a write. A reviewer is accountable for the arguments they saw.

## Decision

Every write tool in the registry requires approval. The run pauses in `APPROVAL_REQUIRED`. Approval resumes the same run. Rejection writes nothing and still completes the answer. Approvals expire.

## Consequences

The happy path is slower than an agent that calls tools inline. The wait is a metric (`approval wait`) because it is part of the product, not an accident.
