# ADR-002: Python AI runtime

## Status

Accepted

## Context

Retrieval, PDF extraction, embedding, and model HTTP clients are where the AI work actually is. Putting that inside the Spring process would couple request threads to native libraries and model timeouts.

## Decision

A Python service plans a run: retrieve, compose an answer, optionally propose one tool. A Python worker performs embedding, ticket execution, and evaluation. Shared code sits in `py/aegislib`.

## Consequences

Two languages means a JSON contract instead of an in-process call. The contract is documented in `docs/api-boundary.md`. The runtime is not given a path that inserts tickets.
