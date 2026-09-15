#!/bin/sh
set -eu
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=app_password="$CODEGRID_DB_PASSWORD" <<'SQL'
CREATE ROLE codegrid_app LOGIN PASSWORD :'app_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
ALTER SCHEMA public OWNER TO codegrid_app;
GRANT CONNECT ON DATABASE codegrid TO codegrid_app;
SQL
