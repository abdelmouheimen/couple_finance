# CoupleFinance

Mobile app for a couple sharing household finances: shared and personal expenses, receipt capture with AI
analysis (always validated and confirmed by a human), budgets, savings goals and analytics.

> Status: **project skeleton**. No business feature is implemented yet.

## Repository layout

| Path | Content |
|---|---|
| `backend/` | Spring Boot modular monolith (Java 25, Gradle, PostgreSQL, Liquibase). |
| `mobile/` | React Native / TypeScript app — not initialised yet, see [mobile/README.md](mobile/README.md). |
| `infra/` | Local development services (Docker Compose PostgreSQL). |
| `api/openapi.yaml` | Committed OpenAPI contract, generated from the backend, consumed by the mobile app. |
| `docs/` | Product, architecture, domain model, database schema and ADRs — the source of truth. |
| `CLAUDE.md` | Engineering rules (also for Claude Code). |

Start with [docs/product/vision.md](docs/product/vision.md) and
[docs/architecture/architecture.md](docs/architecture/architecture.md).

## Prerequisites

- **JDK 25** — Gradle uses a Java 25 toolchain. If no JDK 25 is installed, it is downloaded automatically.
  Gradle itself must run on JDK 17+ (`JAVA_HOME`).
- **Docker** — for the local database and for the tests (Testcontainers).
- No Gradle installation needed: use the wrapper (`./gradlew`, or `gradlew.bat` on Windows).

## Run the backend locally

### Option A — Docker Compose database

```bash
docker compose -f infra/docker-compose.yml up -d --wait   # PostgreSQL 17 on 127.0.0.1:5432
cd backend
./gradlew bootRun                                         # uses the `local` profile by default
```

If port 5432 is already taken, pick another one for both the database and the backend:

```bash
cp infra/.env.example infra/.env        # then edit POSTGRES_PORT, e.g. 55432
docker compose -f infra/docker-compose.yml up -d --wait
cd backend && POSTGRES_PORT=55432 ./gradlew bootRun
```

Stop the database with `docker compose -f infra/docker-compose.yml down` (add `-v` to delete its data).

### Option B — throw-away Testcontainers database

```bash
cd backend
./gradlew bootTestRun
```

Starts a disposable PostgreSQL container and the application on top of it. Nothing to configure; data is lost
on exit.

### Useful endpoints (local)

| URL | |
|---|---|
| http://localhost:8080/actuator/health | Health (public), also `/actuator/health/liveness` and `/readiness` |
| http://localhost:8080/swagger-ui.html | Swagger UI (local profile only) |
| http://localhost:8080/v3/api-docs.yaml | Generated OpenAPI spec (local and test profiles only) |

Every other endpoint requires authentication (none exists yet, so they answer `401`). All errors are
[RFC 9457 Problem Details](https://www.rfc-editor.org/rfc/rfc9457) with a stable `code` property.

## Build and test

```bash
cd backend
./gradlew build                                   # compile (warnings are errors) + all tests
./gradlew test                                    # tests only
./gradlew test --tests '*GlobalExceptionHandlerTest'
./gradlew updateOpenApi                           # regenerate api/openapi.yaml after an API change
```

The tests need a running Docker daemon: integration tests start PostgreSQL 17 with Testcontainers and apply the
Liquibase migrations. The suite also checks module boundaries (Spring Modulith) and fails if
`api/openapi.yaml` no longer matches the code.

## Configuration

| Profile | Database | OpenAPI / Swagger |
|---|---|---|
| *(none)* — production-like | `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD` | disabled |
| `local` | Docker Compose (`POSTGRES_*` variables, dev defaults) | enabled |
| `test` | Testcontainers (`@ServiceConnection`) | API docs enabled |

No secrets are committed. The only credentials in the repository are the development defaults of the local
Docker Compose database.

## Database migrations

Liquibase changelogs live in `backend/src/main/resources/db/changelog/`:

```
db.changelog-master.yaml        # includes one changelog per module, in dependency order
infra/infra.changelog.yaml      # technical foundations (btree_gist, infra schema)
infra/changes/0001-….yaml
```

Rules (see [docs/architecture/database.md](docs/architecture/database.md) §5 and CLAUDE.md §4): every schema
change is a new changeset in the owning module's changelog, named `<module>-<NNNN>-<description>`. Merged
changesets are never edited. Hibernate only validates the schema. The target schema is specified in
[docs/architecture/database-schema.md](docs/architecture/database-schema.md).

## Backend structure

Package-by-feature. Each direct sub-package of `com.couplefinance` is an application module:

```
com.couplefinance
├── CoupleFinanceApplication
└── shared/          # shared kernel (open module)
    ├── error/       # ErrorCode, ApplicationException, GlobalExceptionHandler (Problem Details)
    ├── security/    # deny-by-default security filter chain
    ├── openapi/     # OpenAPI metadata
    └── time/        # Clock bean
```

Business modules (`identity`, `household`, `expense`, `receipt`, …) will be added as siblings of `shared`,
each with `api/`, `domain/`, `application/`, `infrastructure/`, `web/` sub-packages (architecture.md §4.1).
