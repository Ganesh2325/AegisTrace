"""Production start-up checks shared by the runtime and worker.

The functions never print secret values. A development environment is not
constrained by these rules.
"""

from __future__ import annotations

from collections.abc import Mapping


def production_problems(env: Mapping[str, str]) -> list[str]:
    if env.get("AEGIS_ENVIRONMENT", "dev").lower() not in {"prod", "production"}:
        return []
    problems: list[str] = []
    token = env.get("AEGIS_INTERNAL_TOKEN", "")
    if "change-me" in token.lower() or len(token) < 24:
        problems.append("AEGIS_INTERNAL_TOKEN")
    database = env.get("DATABASE_URL", "")
    lowered = database.lower()
    if "change-me" in lowered or "localhost" in lowered or "127.0.0.1" in lowered or database == "":
        problems.append("DATABASE_URL")
    if env.get("SEED_KNOWLEDGE", "false").lower() == "true":
        problems.append("SEED_KNOWLEDGE")
    endpoint = env.get("S3_ENDPOINT", "").strip().lower()
    if endpoint.startswith("http://") or "localhost" in endpoint or "garage" in endpoint or "127.0.0.1" in endpoint:
        problems.append("S3_ENDPOINT")
    openai = env.get("OPENAI_BASE_URL", "").strip().lower()
    if openai.startswith("http://") or "localhost" in openai or "127.0.0.1" in openai:
        problems.append("OPENAI_BASE_URL")
    return problems


def enforce(env: Mapping[str, str]) -> None:
    problems = production_problems(env)
    if problems:
        raise SystemExit("Refusing to start production: " + ", ".join(problems))


def promotion_decision(status: int) -> str:
    return "promote" if status == 200 else "rollback"


def database_url(env: Mapping[str, str]) -> str:
    url = env.get("DATABASE_URL", "")
    password = env.get("DATABASE_PASSWORD", "")
    if not password or "://" not in url or "@" not in url:
        return url
    scheme, rest = url.split("://", 1)
    user, host = rest.split("@", 1)
    if ":" in user:
        return url
    return f"{scheme}://{user}:{password}@{host}"
