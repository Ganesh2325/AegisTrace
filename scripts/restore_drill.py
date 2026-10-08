"""Restore a local database dump into a disposable database and time it.

This does not restore an RDS instance. It proves the dump and restore commands
against the local development database without replacing that database.
"""

from __future__ import annotations

import subprocess
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATABASE = "aegis_restore_drill"


def compose(*args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", *args],
        cwd=ROOT,
        text=True,
        capture_output=True,
        check=False,
    )


def main() -> None:
    compose("psql", "-U", "aegis", "-d", "postgres", "-c", f"drop database if exists {DATABASE}")
    started = time.perf_counter()
    dump = compose("pg_dump", "-U", "aegis", "-d", "aegistrace", "--no-owner")
    if dump.returncode != 0:
        raise SystemExit("dump failed")
    created = compose("psql", "-U", "aegis", "-d", "postgres", "-c", f"create database {DATABASE}")
    if created.returncode != 0:
        raise SystemExit("create database failed")
    restore = subprocess.run(
        ["docker", "compose", "exec", "-T", "postgres", "psql", "-U", "aegis", "-d", DATABASE],
        cwd=ROOT,
        input=dump.stdout,
        text=True,
        capture_output=True,
        check=False,
    )
    if restore.returncode != 0:
        compose("psql", "-U", "aegis", "-d", "postgres", "-c", f"drop database if exists {DATABASE}")
        raise SystemExit("restore failed")
    check = compose(
        "psql", "-U", "aegis", "-d", DATABASE, "-tAc",
        "select count(*) from flyway_schema_history",
    )
    elapsed = time.perf_counter() - started
    compose("psql", "-U", "aegis", "-d", "postgres", "-c", f"drop database if exists {DATABASE}")
    count = check.stdout.strip()
    if check.returncode != 0 or not count.isdigit() or int(count) < 1:
        raise SystemExit("restored history was not readable")
    print(f"restore_drill migrations={count} seconds={elapsed:.2f}")


if __name__ == "__main__":
    main()
