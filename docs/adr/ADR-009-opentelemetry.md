# ADR-009: OpenTelemetry

## Status

Accepted

## Context

A run crosses the browser, the API, the runtime, the database, the worker, and the tool. Logs alone do not show which hop was slow.

## Decision

Services export OTLP traces. The control plane also exposes Prometheus metrics. Log lines include `trace_id`, `run_id`, `workspace_id`, and `user_id` when they exist. Span and log bodies do not include prompts or retrieved text by default.

A single `agent.run` span is not held open across the human approval wait. Approval can last minutes and must survive a process restart. Each activation (plan, resume) is its own trace, tagged with `run.id`. The timeline in Postgres is the durable narrative. Jaeger is how you see latency inside one activation.

## Consequences

An engineer follows one run id through logs, the timeline, and Jaeger search on `run.id`. They do not get one infinite span that disappears if the API restarts while a reviewer is away.
