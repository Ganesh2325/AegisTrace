# ADR-010: Docker Compose instead of Kubernetes

## Status

Accepted

## Context

The MVP is four processes, one database, one cache, and one object store. Kubernetes would add a control plane we would have to operate before the product control plane was finished.

## Decision

Local, test, and demo environments run with Docker Compose. The AWS shape is ECS services, RDS, ElastiCache, and S3, described in Terraform. Kubernetes is not introduced unless a later requirement cannot be met with ECS.

## Consequences

Compose does not schedule a failed host. That is acceptable locally. In AWS, ECS restarts unhealthy tasks. We do not pretend Compose is a production orchestrator.
