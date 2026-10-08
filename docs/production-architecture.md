# Production architecture

This document describes the production boundary. The Terraform under `infrastructure/aws` is the contract for that boundary. It has not been applied: this workspace has no AWS CLI credentials and no live account resources. Local Docker Compose remains the running system.

## Component classification

| Component | Classification | Production decision |
|---|---|---|
| Next.js frontend | Production required | ECS Fargate service behind the load balancer |
| Spring Boot control plane | Production required | Private ECS service. The browser reaches it only through the frontend `/backend` proxy |
| Python runtime | Production required | Private ECS service. It proposes; it does not approve or execute writes |
| Python worker | Production required | Private ECS service, desired count 1 |
| PostgreSQL with pgvector | Replaced by managed service | RDS PostgreSQL 16, private, encrypted, seven-day backups |
| Redis | Production required | ElastiCache Redis 7, private, encrypted. Shared rate limits and run fan-out |
| Garage | Local only | Replaced by private S3 |
| OpenTelemetry collector, Jaeger, Prometheus, Grafana | Optional in the cloud account | Local telemetry stays on Compose. CloudWatch logs, Container Insights, and three infrastructure alarms are the managed path. Prometheus rules and the operations dashboard remain the metric definitions |
| Scheduled maintenance | Production required | Existing in-process maintenance. No separate scheduler was added |

Kubernetes is not used. Four services do not justify operating a cluster.

## Environments

Development is the current Compose project. It may seed accounts and use the local `change-me` values.

Staging and production are separate Terraform environments. Each apply creates its own VPC, database, Redis, bucket, secrets, and cluster. They do not share data. Production startup rejects seed data, failure simulation, development secrets, local database URLs, custom object-storage endpoints, static object-storage keys, and a missing Redis password. The session cookie must be Secure and the frontend origin must be HTTPS.

`docker-compose.staging.yml` only shows the local configuration shape with seeding disabled. It is not a cloud staging environment and it was not started over the development stack.

## Network

Public subnets hold the load balancer and one NAT gateway. Application tasks, RDS, and Redis are in private subnets with no public addresses. The load balancer accepts HTTPS and redirects HTTP. Its security group can reach only the frontend. The control plane accepts traffic from the frontend and the worker. The runtime accepts traffic from the control plane. PostgreSQL accepts the control plane, runtime, and worker. Redis accepts the control plane. Object storage uses a gateway endpoint. Other egress uses the NAT gateway for ECR, Secrets Manager, CloudWatch, and the model provider on port 443.

No domain is configured. HTTPS requires an ACM certificate ARN before a plan can succeed. DNS is not delegated.

## Identity and secrets

GitHub Actions assumes a deploy role through OIDC. The trust policy allows only this repository's staging and production environments. There is no long-lived access key in the workflow. Task secrets are injected from Secrets Manager. Object storage uses the task role. The control plane database user is `aegis_migrate`. The runtime and worker use `aegis_app`, which the bootstrap SQL limits to data changes. Those roles are not created until the SQL is applied to a real database.

## Release identity

An administrator can read `GET /api/v1/admin/release`. The response contains the environment, `AEGIS_RELEASE_SHA`, and `AEGIS_IMAGE_DIGEST`. It does not contain secrets.

## Scaling

The frontend and control plane are stateless aside from the database and Redis. Session cookies remain valid across tasks because the signing secret is shared. Rate limits are shared through Redis. Workers claim jobs with `FOR UPDATE SKIP LOCKED` and owner checks, so a second worker does not execute the same job. Desired counts are 1. The local worker still processes one job at a time. This is not an autoscaling claim.

## Cost estimate

On-demand us-east-1 list prices for one small environment, one task of each service, single-AZ database and cache, and one NAT gateway:

| Item | Assumption | Monthly estimate |
|---|---|---|
| NAT gateway | $0.045 per hour | $33 |
| Application load balancer | $0.0225 per hour, low LCU | $22 |
| RDS `db.t4g.micro` | $0.016 per hour plus 20 GB | $15 |
| ElastiCache `cache.t4g.micro` | $0.016 per hour | $12 |
| Fargate | frontend 0.25 vCPU / 0.5 GB; three services 0.5 vCPU / 1 GB | $55 |
| CloudWatch logs | about 5 GB ingested | $5 |
| S3, ECR, Route 53 | demo volume | $3 |
| Data transfer | light demo traffic | $5 |

The planning total is about $150 per environment per month. Staging and production together are about $300 if both stay on. The largest avoidable cost is a second always-on copy. Prices are list estimates, not an invoice.

## What is not live

No VPC, load balancer, certificate, database, bucket, cluster, image digest, or deployment exists in AWS. OIDC has not been assumed. Staging and production smoke tests have not run. Rollback has not been observed on ECS. The local restore drill is the measured recovery evidence.
