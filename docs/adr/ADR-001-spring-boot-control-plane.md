# ADR-001: Spring Boot control plane

## Status

Accepted

## Context

Authorization, approvals, and run state have to be transactional and boring. The team needs schema migrations, a security filter chain, and an API that the UI can call without embedding policy in Python or in the browser.

## Decision

The control plane is a single Spring Boot 3.4 service on Java 21. Policy, approvals, RBAC, audit, and the public API live there.

## Consequences

Java is more verbose than writing the API in Python. That cost is accepted because the authorization path should look like ordinary enterprise backend code: transactions, constraints, and tests that do not boot a model. We do not split policy into its own service until a separate release cadence exists.
