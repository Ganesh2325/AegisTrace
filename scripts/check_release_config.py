"""Fail when the production contract drifts back toward local defaults."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    problems: list[str] = []
    prod = (ROOT / "control-plane/src/main/resources/application-prod.yml").read_text(encoding="utf-8")
    for required in ("seed-enabled: false", "failure-simulation-enabled: false", "cookie-secure: true", "port: 8081"):
        if required not in prod:
            problems.append(f"application-prod.yml missing {required}")
    data = (ROOT / "infrastructure/aws/data.tf").read_text(encoding="utf-8")
    if "publicly_accessible         = false" not in data and "publicly_accessible = false" not in data:
        problems.append("database is not marked private")
    if "deletion_protection" not in data:
        problems.append("database deletion protection is missing")
    if "block_public_acls       = true" not in data:
        problems.append("object storage public access block is missing")
    example = (ROOT / "deploy/production.env.example").read_text(encoding="utf-8")
    if "change-me" in example:
        problems.append("production example contains a development secret")
    for key in ("AEGIS_JWT_SECRET=", "AEGIS_INTERNAL_TOKEN=", "POSTGRES_PASSWORD=", "S3_ENDPOINT="):
        if key not in example:
            problems.append(f"production example missing {key}")
    staging = (ROOT / "docker-compose.staging.yml").read_text(encoding="utf-8")
    if 'AEGIS_SEED_ENABLED: "false"' not in staging or 'SEED_KNOWLEDGE: "false"' not in staging:
        problems.append("staging overlay still seeds data")
    if problems:
        raise SystemExit("\n".join(problems))
    print("release configuration contract: ok")


if __name__ == "__main__":
    main()
