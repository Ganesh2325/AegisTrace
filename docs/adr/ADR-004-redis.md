# ADR-004: Redis

## Status

Accepted

## Context

The UI wants live run updates. More than one API instance cannot share an in-memory SSE map. Redis is the usual answer, and it is also the usual place people accidentally put the only copy of a job.

## Decision

Redis publishes run events for SSE fan-out. It is optional at readiness time. The job queue, approval state, and tickets are in PostgreSQL.

## Consequences

A Redis outage degrades live updates. It does not drop an approval or duplicate a ticket. Operators still see state by reading the run API, which the UI polls if the event stream errors.
