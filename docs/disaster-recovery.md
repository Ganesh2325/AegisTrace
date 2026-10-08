# Disaster recovery

The cloud account is not provisioned. These procedures distinguish a configured capability from a measured local drill. There is no multi-region recovery.

## Database

RDS, when applied, keeps seven days of automated backups and point-in-time recovery. It is single-AZ, so a failure is a restore or an instance restart, not a standby failover. Deletion protection is on for production.

Recovery point objective for that configuration is the latest restorable time inside the seven-day window. Recovery time was not measured on RDS.

The local drill dumps the development database, restores it into `aegis_restore_drill`, reads `flyway_schema_history`, and drops the disposable database. The development database is left in place. The measured run restored 9 migration versions in 5.83 seconds.

Migrations are rehearsed by running Flyway against `aegis_migration_drill` and then dropping it. That rehearsal applied the committed migrations through version 9. A failed migration stops that command. It does not continue into a deployment.

## Object storage

The S3 bucket blocks public access, denies non-TLS requests, encrypts with AES-256, versions objects, aborts incomplete uploads after seven days, and expires noncurrent versions after 30 days. Object keys remain `documents/{workspace}/{knowledge-base}/{sha256}`. An unreferenced checksum object is not public. No cloud bucket exists yet.

## Application

Images are immutable in ECR once the repositories exist. A bad deployment is rolled back by the ECS circuit breaker to the previous task definition. Workers keep job ownership in PostgreSQL, so a restarted worker does not repeat a completed ticket.

## Loss of a dependency

| Loss | Expected behavior | Measured here |
|---|---|---|
| Control-plane task | ECS restarts it. Flyway locks avoid concurrent schema changes | Not measured on ECS |
| Worker task | Unfinished jobs remain leased, then become claimable | Existing lease tests |
| Runtime | Plans fail until readiness returns. The control plane does not execute the write itself | Not measured on ECS |
| Redis | Production readiness fails when Redis is required and down. The deployment does not count as healthy | Startup guard |
| Object storage | Uploads return a dependency error. Retrieval of stored documents fails closed | Existing storage errors |
| Telemetry | Traces export failures do not authorize actions | Existing privacy tests |

## Role boundary

`scripts/db_role_drill.py` creates `aegis_app_drill` on a disposable database, grants data changes, attempts to drop the table, and removes the role. The development login is unchanged.
