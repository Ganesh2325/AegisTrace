# ADR-008: Immutable agent versions

## Status

Accepted

## Context

If a prompt or a threshold can change under an old run, the trace cannot be explained and an evaluation cannot be compared.

## Decision

Prompt versions and agent versions are insert-only. Starting a run copies the snapshot onto the run row. The UI edits by creating a new version and activating it. Historical runs keep their original version ids.

## Consequences

Fixing a prompt does not rewrite the past. Operators may see different behavior on either side of a version boundary. That is the point of storing the version on the run.
