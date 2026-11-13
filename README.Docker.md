# Preserved PostgreSQL setup

The MVP server and automated tests use SQLite. Docker is unnecessary for normal development and testing.

The existing .env database credentials, PostgreSQL service/container name, localhost:5433 port mapping and persistent Compose volume remain unchanged. To start the retained database service for later migration work:

```powershell
docker compose up -d postgres
```

Connection bindings remain in application-postgres.properties. Historical PostgreSQL migration versions/names/contents V5–V7 are preserved; V8 maps legacy employee roles. They are stored separately from SQLite migrations.

The new tenant, ledger, count and commerce schema currently targets SQLite. Starting the server with the postgres profile deliberately fails before database initialization, rather than running incomplete services against the retained database. Complete the schema/data migration and rerun tenant/concurrency/restore tests against PostgreSQL during [the post-MVP review](POST_MVP_REVIEW.md).

No SQLite data is automatically transferred, no PostgreSQL data is reset, and no volume is deleted. The former untested application Dockerfile was removed during baseline cleanup. Application containerization/cloud deployment remain future reviewed work.
