from aegislib.production import database_url, production_problems, promotion_decision


def test_development_configuration_is_not_rejected():
    assert production_problems({"AEGIS_ENVIRONMENT": "dev", "AEGIS_INTERNAL_TOKEN": "change-me-internal-token"}) == []


def test_production_rejects_local_defaults():
    problems = production_problems({
        "AEGIS_ENVIRONMENT": "prod",
        "AEGIS_INTERNAL_TOKEN": "change-me-internal-token",
        "DATABASE_URL": "postgresql://aegis:change-me-postgres-dev-only@localhost:5432/aegistrace",
        "SEED_KNOWLEDGE": "true",
        "S3_ENDPOINT": "http://garage:3900",
        "OPENAI_BASE_URL": "http://127.0.0.1:8000/v1",
    })
    assert problems == [
        "AEGIS_INTERNAL_TOKEN",
        "DATABASE_URL",
        "SEED_KNOWLEDGE",
        "S3_ENDPOINT",
        "OPENAI_BASE_URL",
    ]


def test_production_accepts_managed_endpoints():
    assert production_problems({
        "AEGIS_ENVIRONMENT": "production",
        "AEGIS_INTERNAL_TOKEN": "a-real-internal-token-value",
        "DATABASE_URL": "postgresql://aegis_app:secret@aegistrace.example.us-east-1.rds.amazonaws.com:5432/aegistrace",
        "SEED_KNOWLEDGE": "false",
        "S3_ENDPOINT": "",
        "OPENAI_BASE_URL": "https://api.openai.com/v1",
    }) == []


def test_database_password_is_inserted_without_replacing_an_existing_secret():
    assert database_url({
        "DATABASE_URL": "postgresql://aegis_app@db.internal:5432/aegistrace",
        "DATABASE_PASSWORD": "managed-secret",
    }) == "postgresql://aegis_app:managed-secret@db.internal:5432/aegistrace"
    assert database_url({
        "DATABASE_URL": "postgresql://aegis:already@db.internal:5432/aegistrace",
        "DATABASE_PASSWORD": "other",
    }) == "postgresql://aegis:already@db.internal:5432/aegistrace"


def test_failed_health_rolls_back():
    assert promotion_decision(200) == "promote"
    assert promotion_decision(503) == "rollback"
    assert promotion_decision(0) == "rollback"
