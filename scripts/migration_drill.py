"""Apply the committed Flyway migrations to a disposable database.

The development database is not dropped or migrated by this script.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATABASE = "aegis_migration_drill"


def psql(statement: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "aegis", "-d", "postgres", "-c", statement],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def main() -> None:
    psql(f"drop database if exists {DATABASE}")
    created = psql(f"create database {DATABASE}")
    if created.returncode != 0:
        raise SystemExit("create database failed")
    flyway = subprocess.run(
        [
            "docker", "run", "--rm", "--network", "newproject_default",
            "-v", f"{ROOT / 'control-plane/src/main/resources/db/migration'}:/flyway/sql:ro",
            "flyway/flyway:10.22.0",
            f"-url=jdbc:postgresql://postgres:5432/{DATABASE}",
            "-user=aegis",
            "-password=change-me-postgres-dev-only",
            "-locations=filesystem:/flyway/sql",
            "migrate",
        ],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    if flyway.returncode != 0:
        psql(f"drop database if exists {DATABASE}")
        raise SystemExit("migration failed")
    check = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "aegis", "-d", DATABASE, "-tAc",
         "select max(version) from flyway_schema_history"],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    psql(f"drop database if exists {DATABASE}")
    version = check.stdout.strip()
    if check.returncode != 0 or version == "":
        raise SystemExit("migration history was not readable")
    print(f"migration_drill version={version}")


if __name__ == "__main__":
    main()
