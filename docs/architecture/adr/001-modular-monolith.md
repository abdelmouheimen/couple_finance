# ADR-001 — Modular monolith as the backend architecture

- **Status:** Accepted
- **Date:** 2026-09-28
- **Deciders:** Architecture / product team

## Context

CoupleFinance is a new product with:

- a small team (one backend codebase, one mobile codebase) and uncertain product-market fit;
- a domain with several clearly identifiable capabilities (identity, household, expenses, receipts,
  categorisation, budgets, savings, analytics, insights, assistant) that will evolve at different speeds;
- strong consistency needs on money (an expense, its audit trail and the resulting budget state must be
  consistent);
- an AI component that must be isolated from business logic and replaceable;
- modest expected load (thousands to tens of thousands of households; tens of writes per household per week).

Options considered:

1. **Layered monolith** (controller / service / repository across the whole app).
2. **Modular monolith** — single deployable, internally split into domain modules with enforced boundaries.
3. **Microservices** — one service per capability, separate databases, network communication.

## Decision

We build the backend as a **modular monolith**:

- One Spring Boot application (Java 25), one deployable artifact, one PostgreSQL database.
- Modules follow business capabilities (see [architecture.md §4](../architecture.md#4-modules)).
- Each module owns a **dedicated PostgreSQL schema** and its Liquibase changelog; no module accesses
  another module's tables.
- Modules interact only through a **public API package** (facades, DTOs, events). Internals are
  package-private.
- Asynchronous reactions use **domain events** published via Spring Modulith's transactional event
  publication registry (outbox in PostgreSQL).
- Boundaries are **verified automatically** in the build (Spring Modulith `ApplicationModules.verify()` and
  ArchUnit rules). A violation fails CI.
- The `ai` module exposes ports; provider adapters are internal to it.

## Consequences

### Positive

- Simple operations: one build, one deployment, one database, local transactions — suits a small team.
- Strong consistency where it matters (expense + audit + outbox event in a single ACID transaction).
- Refactoring across modules is cheap while the domain is still being discovered.
- Clear seams: a module with its own schema, API and events can be extracted into a service later if a real
  need appears (independent scaling of AI processing, team growth).
- Testing is straightforward: module tests plus full-application tests with Testcontainers.

### Negative / risks

- Boundaries can erode without discipline → mitigated by automated verification and code review.
- A single deployable means one module's bug or resource spike (e.g. receipt processing) can affect the
  whole app → mitigated by async jobs with bounded concurrency, timeouts, circuit breakers; receipt workers
  can later run as a separate process of the same artifact (profile-based) before any real extraction.
- One database is a shared failure domain and scaling unit → acceptable at expected load.
- Cross-module queries (analytics) cannot simply JOIN other modules' tables → analytics uses module read
  APIs, or, if needed, its own event-fed projections. We accept a limited exception: analytics may run
  read-only SQL on `expense` data **through a query API owned by the expense module**, never directly.

### Neutral

- Cross-module foreign keys are restricted to the stable core (`household`, `identity`); other references are
  ids validated by the application (see [database.md](../database.md#cross-module-references)).

## Alternatives rejected

- **Layered monolith**: fastest start but no boundaries; AI and business logic would tend to mix, and later
  extraction would be costly.
- **Microservices**: distributed transactions/sagas for simple money flows, network failure modes, multiple
  pipelines and databases — high cost with no current scaling or organisational need.

## Revisit when

- A module needs independent scaling or a different runtime (e.g. heavy AI workloads, GPU/OCR).
- More than ~3 teams work on the backend concurrently.
- Deployment frequency of one module is blocked by others.
