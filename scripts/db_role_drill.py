"""Prove a non-owner role cannot drop tables, then remove the drill role.

The role name is drill-specific. The development login is not altered.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATABASE = "aegis_role_drill"
ROLE = "aegis_app_drill"


def sql(database: str, statement: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "aegis", "-d", database, "-v", "ON_ERROR_STOP=1", "-c", statement],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def main() -> None:
    sql("postgres", f"drop database if exists {DATABASE}")
    sql("postgres", f"drop role if exists {ROLE}")
    created = sql("postgres", f"create database {DATABASE}")
    if created.returncode != 0:
        raise SystemExit("create database failed")
    role = sql(DATABASE, f"create role {ROLE} login nosuperuser nocreatedb nocreaterole")
    table = sql(DATABASE, "create table drill_guard (id int primary key)")
    grant = sql(DATABASE, f"grant select, insert, update, delete on drill_guard to {ROLE}")
    if any(step.returncode != 0 for step in (role, table, grant)):
        cleanup()
        raise SystemExit("role setup failed")
    dropped = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", ROLE, "-d", DATABASE, "-c", "drop table drill_guard"],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )
    cleanup()
    denied = dropped.returncode != 0 and (
        "must be owner" in dropped.stderr.lower() or "permission denied" in dropped.stderr.lower()
    )
    if not denied:
        raise SystemExit("drill role was not denied for the expected reason")
    print("db_role_drill drop_denied=true")


def cleanup() -> None:
    sql("postgres", f"drop database if exists {DATABASE}")
    sql("postgres", f"drop role if exists {ROLE}")


if __name__ == "__main__":
    main()
