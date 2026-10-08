-- Bootstrap roles for a managed database. Run once as the RDS master user.
-- Passwords come from Secrets Manager at apply time. Do not store them in this file.
-- Do not run this against the local development database.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aegis_migrate') THEN
    CREATE ROLE aegis_migrate LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'aegis_app') THEN
    CREATE ROLE aegis_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;
  END IF;
END $$;

GRANT CONNECT ON DATABASE aegistrace TO aegis_migrate, aegis_app;
GRANT USAGE, CREATE ON SCHEMA public TO aegis_migrate;
GRANT USAGE ON SCHEMA public TO aegis_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO aegis_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO aegis_app;
ALTER DEFAULT PRIVILEGES FOR ROLE aegis_migrate IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO aegis_app;
ALTER DEFAULT PRIVILEGES FOR ROLE aegis_migrate IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO aegis_app;
