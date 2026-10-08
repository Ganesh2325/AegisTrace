# Deployment

## Local development

`docker compose up` remains the development environment. It uses seed accounts, local object storage, and the development secrets in Compose. Do not point it at a production database or bucket.

## Contract

`infrastructure/aws` validates with Terraform 1.9.8. A plan needs image digests, `frontend_origin`, and `certificate_arn`. The HTTPS listener refuses an empty certificate. Production sets deletion protection on the database. Staging does not, so that environment can be removed.

Images are built from the Dockerfiles and tagged by digest in ECR repositories with tag immutability and scan on push. The running task records the digest in `AEGIS_IMAGE_DIGEST`.

## Order

1. Configure an encrypted remote Terraform backend. Local state would contain generated secrets, so do not apply with a local backend.
2. Apply one environment and note the database address, bucket, and deploy role.
3. As the RDS master, set the `aegis_migrate` and `aegis_app` passwords from Secrets Manager and apply `infrastructure/db/app-role.sql`.
4. Start the control plane. Flyway runs from that process and stops startup when a migration fails. There are no destructive down migrations. A bad migration is repaired forward.
5. Confirm readiness, then run `scripts/release_gate.py` against the public readiness URL. A non-200 result prints `rollback` and exits non-zero.

The deploy workflow is manual. It assumes the environment role, forces a new deployment of the four services, waits until ECS reports them stable, and then runs the readiness gate. It fails closed when the role, region, cluster, or smoke URL is missing. It has not been executed.

## Rollback

ECS deployment circuit breakers are enabled with rollback. A failed health check returns the service to the previous task definition. Application rollback is safe only while migrations stay compatible with the previous release. Flyway will not reverse a committed migration.

The promotion decision is covered by `promotion_decision`: HTTP 200 promotes, every other status rolls back. An ECS rollback duration was not measured because no service was deployed.

## Configuration

`deploy/production.env.example` lists the required names. Production values are injected at runtime. Missing or development secrets fail the control-plane start. The production profile also moves management endpoints, including Prometheus, to port 8081 so the public task port does not serve them. The load balancer does not route that port.

## Worker and runtime

The worker starts with `SEED_KNOWLEDGE=false` in production and refuses local database URLs, development tokens, and a Garage endpoint. The runtime refuses the same class of configuration and rejects failure-simulation requests. Neither service is placed on the load balancer.
