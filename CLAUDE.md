# CLAUDE.md — CoupleFinance engineering rules

CoupleFinance is a mobile app for a couple sharing household finances: Spring Boot backend (modular monolith) +
React Native / TypeScript mobile app.

These rules are **mandatory**. The approved design lives in `docs/` and is the source of truth:

| Topic | Document |
|---|---|
| Product scope, features | `docs/product/vision.md`, `docs/product/features.md` |
| Business rules (`BR-xxx`) | `docs/product/business-rules.md` |
| Architecture, modules, flows, API conventions | `docs/architecture/architecture.md` |
| Security & privacy | `docs/architecture/security.md` |
| AI design | `docs/architecture/ai.md` |
| Domain model (aggregates, invariants, authorization) | `docs/architecture/domain-model.md` |
| Database conventions / physical schema | `docs/architecture/database.md` / `docs/architecture/database-schema.md` |
| Decisions | `docs/architecture/adr/` |

When code and docs disagree, stop and ask — do not silently pick one.

## Commands

Run from `backend/` (JDK 25 toolchain; Docker must be running for tests):

| Task | Command |
|---|---|
| Full build (warnings are errors) + all tests | `./gradlew build` |
| Tests only / one class | `./gradlew test` / `./gradlew test --tests '*ClassName'` |
| Regenerate the committed OpenAPI contract after an API change | `./gradlew updateOpenApi` |
| Run against Docker Compose PostgreSQL (`local` profile) | `docker compose -f ../infra/docker-compose.yml up -d --wait` then `./gradlew bootRun` |
| Run against a throw-away Testcontainers database | `./gradlew bootTestRun` |

Integration tests use `@IntegrationTest` (`com.couplefinance.support`): one cached Spring context + one
PostgreSQL container for the whole suite — reuse it rather than adding new context configurations. Use
`TestUsers` to seed accounts and `TestTokens` to send real signed access tokens (never bypass token validation
in integration tests). Tests share the database: create fresh users/households per test and scope assertions to them.

---

## 1. How to work
### GitHub Issue autonomous workflow

Development work is driven by GitHub Issues.

When explicitly asked to implement a GitHub Issue, Claude owns the
development lifecycle from issue analysis to Pull Request creation.

#### Workflow

1. Read the issue with `gh issue view <number>`.
2. Read `CLAUDE.md` and all relevant `docs/`.
3. Inspect existing implementation and tests before changing code.
4. Check that the working tree is clean.
5. Checkout `main` and pull the latest changes.
6. Create a branch from the updated `main`.
7. Implement only the scope defined by the Issue.
8. Add/update Liquibase migrations when required.
9. Update OpenAPI when required.
10. Add/update tests required by this file and the Issue.
11. Run relevant tests.
12. Run the complete affected build/test suite.
13. Review the complete git diff.
14. Fix BLOCKER/HIGH issues found during self-review.
15. Commit using Conventional Commits.
16. Push the feature branch.
17. Create a Pull Request.
18. Link the Pull Request to the Issue with `Closes #<issue-number>`.
19. Stop and wait for human PR review.

#### Branch naming

Feature:

`feature/<issue-number>-<short-description>`

Example:

`feature/1-create-household`

Bug:

`fix/<issue-number>-<short-description>`

#### Commits

Use Conventional Commits.

Examples:

`feat(household): create household (#1)`
`fix(expense): enforce household isolation (#17)`
`test(receipt): add receipt analysis tests (#32)`

Keep commits focused and reviewable.

#### Pull Request requirements

Every Pull Request must contain:

- Issue reference
- Summary
- Business rules implemented
- Technical changes
- Database changes
- API/OpenAPI changes
- Security considerations
- Tests added
- Commands executed and their results
- Known limitations or unresolved risks

The PR body must contain:

`Closes #<issue-number>`

#### Mandatory self-review

Before creating the Pull Request, review the complete diff for:

- business rule violations
- authorization bypasses
- household isolation failures
- security vulnerabilities
- architecture/module boundary violations
- transaction problems
- concurrency/race conditions
- monetary calculation errors
- missing database constraints
- missing tests
- unnecessary complexity
- unrelated changes
- AI trust-boundary violations when applicable

Classify findings as:

- BLOCKER
- HIGH
- MEDIUM
- LOW

BLOCKER and HIGH findings must be fixed before creating the PR.

MEDIUM and LOW findings that are intentionally not fixed must be
documented in the Pull Request.

#### Failure policy

Never create a Pull Request presented as ready for review when:

- the project does not compile;
- required tests fail;
- integration tests fail;
- architecture tests fail;
- OpenAPI drift exists;
- a known BLOCKER/HIGH security issue remains.

Do not bypass, disable, delete, weaken or ignore a failing check merely
to complete an Issue.

If completion is blocked, stop implementation and report the blocker.

#### Agent permissions

Claude may autonomously:

- create feature/fix branches;
- modify files;
- create Liquibase migrations;
- update OpenAPI;
- run builds and tests;
- create commits;
- push feature/fix branches;
- create Pull Requests.

Claude MUST NOT autonomously:

- push directly to `main`;
- merge Pull Requests;
- force-push `main`;
- delete protected branches;
- modify branch protection;
- disable CI/security checks;
- merge despite failing checks.

The human reviewer owns the final Pull Request review and merge decision.

#### Architecture decisions

The autonomous GitHub workflow does NOT override the architectural
approval rules in this file.

If implementation requires:

- changing module boundaries;
- introducing a framework or major dependency;
- changing the authorization model;
- changing the money model;
- changing the AI trust model;
- contradicting an ADR;
- contradicting an approved business rule;

stop and request human approval before implementing that architectural
change.
- **Inspect existing patterns before creating new ones.** Before adding a class, endpoint, migration, test or
  utility, look at how the same thing is already done in the codebase and follow it.
- **Prefer small changes.** One concern per change; keep diffs reviewable.
- **Do not refactor unrelated code.** No drive-by renames, reformatting or "cleanups" outside the task.
- **Never make large architectural changes silently.** Adding or removing a module, changing module
  boundaries or dependencies, introducing a new framework/library/infrastructure component, changing the money
  model, the authorization model, the AI trust model or an ADR decision: **explain the change and its trade-offs
  first and wait for approval** before implementing. Approved changes get a new ADR in `docs/architecture/adr/`.
- Reference business rules by id (`BR-EXP-08`) in code comments where the rule is non-obvious, and in test names.
- If a requirement is ambiguous or a business rule is missing, ask rather than invent one.

## 2. Architecture

- **Modular monolith**: one Spring Boot application, one deployable, one PostgreSQL database
  (ADR-001).
- **Domain-oriented packages**: modules follow business capabilities (`identity`, `household`, `expense`,
  `categorization`, `receipt`, `budget`, `savings`, `analytics`, `insight`, `assistant`, `notification`, `ai`,
  plus the `shared` kernel) — never technical layers at top level.
- Package layout per module:
  ```
  com.couplefinance.<module>
  ├── api/            # public facades, DTOs, events — the ONLY package other modules may import
  ├── domain/         # entities, value objects, domain services, repository ports
  ├── application/    # use cases: transaction boundary, authorization, orchestration
  ├── infrastructure/ # persistence, external adapters, Liquibase changelog of the module
  └── web/            # REST controllers, request/response DTOs, mapping
  ```
- **Clear module boundaries**:
  - Modules interact only through `<module>.api` (synchronous facades) or domain events.
  - No access to another module's repositories, entities or tables; no cross-module JPA relationships —
    reference other modules' data by id.
  - No dependency cycles; use events for reactions.
  - Boundaries are verified by Spring Modulith (`ApplicationModules.verify()`) and ArchUnit. Never weaken or
    suppress these checks to make a build pass.
- **Controllers contain no business logic**: they parse/validate the request DTO, call one application use case,
  map the result to a response DTO. No calculations, no repository calls, no branching on business state.
- **Repositories contain no business logic**: data access only. No calculations or rules in repositories,
  queries excepted (aggregations in SQL are allowed where the design says so, e.g. the spending query API).
- Business rules live in `domain` (invariants) and `application` (use cases).
- Domain events are published via the Spring Modulith event publication registry (transactional outbox);
  listeners must be **idempotent** and re-read current state.
- **Never hold a database transaction or pooled connection during an outbound call** (AI provider, object
  storage, email, push).

## 3. Java

- **Java 25.** Use modern language features where they improve clarity (records, sealed types, pattern matching,
  switch expressions).
- **Prefer records for immutable DTOs**, API models, events and value objects.
- **Money**:
  - `BigDecimal` for all monetary values, always wrapped in the `Money` value object (amount + currency).
  - **No floating point for money** — never `double`, `float`, `Double`, `Float` for amounts, rates applied to
    money, or percentages computed from money.
  - Never use `new BigDecimal(double)`; never compare with `equals` when scale may differ (use `compareTo`).
  - Rounding is explicit (`RoundingMode.HALF_EVEN` unless a rule says otherwise) and applied once, at the end.
  - Splitting amounts follows BR-MON-06 (remainder distributed deterministically; parts sum exactly).
- **Time**:
  - `Instant` for technical timestamps (created/updated/occurred at, token expiry). Stored as `TIMESTAMPTZ`.
  - `LocalDate` for business dates (expense date, budget period bounds, goal target date).
  - Never call `Instant.now()` / `LocalDate.now()` directly in business code — inject `java.time.Clock`.
  - "Today" for a household is computed in the household timezone (BR-HH-05).
- Use constructor injection; no field injection.
- No `null` returns from public APIs where `Optional` or an empty collection is appropriate.
- Validation: Bean Validation on request DTOs; invariants enforced in domain objects.
- Errors: domain exceptions mapped to RFC 9457 Problem Details with a stable `code`.

## 4. Database

- **PostgreSQL**, one schema per module; a module only touches its own schema.
- **Every schema change requires a Liquibase changeset**, in the owning module's changelog.
  - Merged changesets are **immutable**; fix with a new changeset.
  - Changeset id: `<module>-<NNNN>-<short-description>`; one logical change per changeset; provide `rollback`
    when not derivable.
  - Destructive changes use expand/contract across releases.
  - Hibernate `ddl-auto` stays `validate`; never generate schema from entities.
- **Constraints at DB level** must exist for every invariant the database can express: `NOT NULL`, primary keys,
  foreign keys (within a module, and towards `household`/`identity`), `UNIQUE` (including partial unique indexes),
  `CHECK` (e.g. `amount_minor > 0`, enum values). Application validation does not replace them.
- Money columns: `<name>_minor BIGINT` + `currency CHAR(3)`. Never `numeric` floats, `double precision` or `money`.
- Primary keys: UUIDv7 generated by the application.
- Every table holding household data has `household_id NOT NULL`; tables that can hold personal data have
  `owner_user_id`.
- Mutable aggregates have a `version` column (optimistic locking).
- **Indexes must be justified**: every new index states, in the changeset comment, the query or constraint it
  serves. No speculative indexes. Household-scoped indexes start with `household_id`.
- Use `SELECT … FOR UPDATE` / conditional updates where rules require it (member count, receipt confirmation,
  savings balance); lock multiple rows in a deterministic order.

## 5. API

- **REST**, JSON, base path `/api/v1`.
- **OpenAPI contract**: generated from code (springdoc) and committed to `api/openapi.yaml`. Any API change
  updates the committed spec in the same change; CI fails on drift. Breaking changes require explicit approval.
- **Explicit request/response DTOs** (records) for every endpoint, in `<module>.web`.
- **Never expose JPA entities** in controllers, API responses, events or module facades.
- Money in JSON: `{"amount": "12.50", "currency": "EUR"}` — amount as a **string**.
- Dates `YYYY-MM-DD`; instants ISO-8601 UTC.
- Monetary aggregates carry their `scope` (`HOUSEHOLD` / `PERSONAL`).
- Updates use `If-Match` (412 on stale, 428 when missing); creating POSTs accept `Idempotency-Key`.
- Cursor-based pagination, `limit ≤ 100`.
- Errors: Problem Details; never leak stack traces, SQL, or information about other users/households.

## 6. Security

- **Secure by default**: every endpoint requires authentication unless explicitly listed as public
  (registration, login, token refresh, password reset, email verification). Deny by default.
- **Every household resource requires authorization.** Use cases receive a `HouseholdContext` resolved by the
  `household` module; every repository query on household data is scoped by `household_id` (use scoped methods
  such as `findByIdAndHouseholdId`, never `findById` alone).
- **Never trust a `householdId` from the client.** The household is derived from the authenticated user. If a
  client-supplied id is unavoidable, it must be checked against the caller's `HouseholdContext` before use.
- Another household's resource → **404**, not 403.
- **Personal data is private to its owner** (BR-EXP-07): queries include
  `(owner_user_id IS NULL OR owner_user_id = :currentUser)`; personal data must never leak through totals,
  counts, search, notifications, insights, merchant rules or error messages.
- Dissolved households are read-only (BR-HH-10).
- Background jobs use `SystemHouseholdContext` built from stored `household_id` — never unscoped queries, except
  in dedicated purge classes.
- **No secrets committed**: no keys, passwords, tokens or credentials in code, config, tests or fixtures. Use
  environment variables / secret manager; local defaults only for Docker Compose dev services.
- Logs: pseudonymous ids only; never log tokens, passwords, receipt content, AI prompts/outputs, presigned URLs,
  or amounts together with merchant and user.
- Receipt images: private storage, random keys, short-lived presigned URLs issued after authorization.

## 7. AI

- **LLM output is untrusted input** — treat it like user input from an unknown source. Never execute it, never
  build queries from it, escape it when rendered.
- **Structured output required**: every AI call uses a JSON schema; output that doesn't match is a failure
  (one repair retry at most), never "best effort" parsed.
- **Validate AI results** with deterministic rules (BR-RCP-03 … BR-RCP-14): parsing, ambiguity detection,
  ranges, consistency checks. Never auto-correct a value to make it fit; surface ambiguity to the user.
- **AI cannot write directly to the database.** AI adapters return DTOs; only business use cases persist, and
  only after validation. The `ai` module has no access to business tables.
- **Deterministic calculations remain in Java/SQL.** No LLM computes, rounds, converts or aggregates money
  (BR-MON-08). Insight text uses placeholders; values are injected by the server (BR-AI-10).
- **The user confirms receipt analysis before persistence** as an expense (BR-RCP-02, BR-RCP-10). AI results are
  stored only as drafts until confirmed.
- Business modules depend only on ports in `ai.api`; provider SDKs are allowed only in `ai.infrastructure`.
- Check the relevant user's AI consent before any provider call (BR-AI-05); send only minimal data (BR-AI-06).
- Every AI call is logged (metadata only, no content) with provider, model and prompt version.
- Prompts are versioned files; changing a prompt or model requires running the evaluation dataset.

## 8. Testing

- **Unit tests for business rules**: every rule implemented gets tests named after it (e.g.
  `BR_EXP_08_expense_items_must_sum_to_amount`). Money, expense items and parsers also get property-based tests.
- **Integration tests for repositories and the API** against a real database.
- **Testcontainers for PostgreSQL** — no H2 or other in-memory substitute. Liquibase migrations run in tests.
- **Authorization tests are mandatory** for every endpoint touching household data:
  - a user of another household gets 404;
  - the partner cannot observe a PERSONAL resource (directly or via aggregates/search);
  - unauthenticated access is rejected;
  - write attempts on a dissolved household are rejected.
- Concurrency-sensitive rules get concurrency tests (receipt confirmation, member count, budget alerts,
  idempotency).
- AI adapters are tested with recorded provider responses (WireMock); business logic is tested with the fake
  AI adapter. Tests never call real AI providers.
- Architecture tests (Spring Modulith, ArchUnit) must stay green.
- Tests are deterministic: fixed `Clock`, no dependence on execution order or wall time.

## 9. Mobile (React Native / TypeScript)

- TypeScript `strict`; no `any` without justification.
- API client is **generated** from `api/openapi.yaml`; never hand-write API types.
- No money arithmetic or aggregation on device beyond input handling; amounts stay strings / integer minor units.
  All totals come from the backend.
- Tokens: refresh token in secure storage only; never in AsyncStorage or logs.
- Client-side validation is UX only; the server is authoritative.

## 10. Definition of Done

A change is done only when **all** of the following hold:

- [ ] **Implementation** complete and consistent with `docs/` and the relevant `BR-xxx` rules.
- [ ] **Tests** added/updated: unit tests for rules, integration tests for repositories/API, authorization tests
      for household resources.
- [ ] **Migrations**: Liquibase changesets for any schema change, with DB constraints and justified indexes.
- [ ] **OpenAPI** spec regenerated and committed for any API change.
- [ ] **Documentation** updated when behaviour, rules, architecture or decisions change (business rules, docs,
      new ADR if applicable).
- [ ] **All checks green**: build, tests (including Testcontainers), architecture tests, OpenAPI diff, linters.
      Never skip, disable or delete a failing test or check to get green — fix the cause or ask.
- [ ] No secrets, no debug leftovers, no unrelated changes in the diff.

When reporting completion, state what was verified and how; if something could not be run, say so explicitly.
