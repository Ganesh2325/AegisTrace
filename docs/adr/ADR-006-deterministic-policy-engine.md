# ADR-006: Deterministic policy engine

## Status

Accepted

## Context

A model can be persuaded, by the user or by a document, to claim that a tool call is allowed. That claim cannot be the authorization decision.

## Decision

`PolicyEngine` is pure Java. It returns `ALLOW`, `DENY`, `REQUIRE_APPROVAL`, or `REQUIRE_ADMIN_APPROVAL` from the tool registry, the agent allowlist, argument schema, role, call count, budget, and priority rules. The model output is input to this function. It is not a branch that skips it.

## Consequences

The engine will refuse work that a helpful model would have done. That is the product. New tools have to be added to the registry and the engine tests before they can run.
