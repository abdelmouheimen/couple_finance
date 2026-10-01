# CLAUDE.md — CoupleFinance

## 0. Project context

CoupleFinance is a mobile-first financial management application for couples.

The application allows two users in the same household to:

- manage shared and personal expenses;
- capture and analyze receipts;
- categorize expenses;
- define monthly budgets;
- track budget consumption;
- analyze spending;
- visualize financial trends;
- manage savings goals;
- receive AI-assisted insights;
- later interact with a read-only financial assistant.

The application handles financial and personal data.

Correctness, security, privacy, deterministic financial calculations and household isolation are first-class requirements.

---

# 1. Sources of truth

Before implementing or modifying behavior, consult the relevant approved documentation.

Product:

- `docs/product/vision.md`
- `docs/product/features.md`
- `docs/product/business-rules.md`

Architecture:

- `docs/architecture/architecture.md`
- `docs/architecture/security.md`
- `docs/architecture/ai.md`
- `docs/architecture/domain-model.md`
- `docs/architecture/database.md`
- `docs/architecture/database-schema.md`
- `docs/architecture/adr/`

API:

- `api/openapi.yaml`

GitHub:

- approved GitHub Issues define implementation work;
- Pull Requests represent proposed implementation changes.

The documentation describes approved desired behavior.

The codebase describes the current implementation.

GitHub Issues describe approved implementation work.

When code, an Issue and approved documentation disagree:

STOP.

Do not silently choose one interpretation.

Report:

- the conflicting sources;
- the conflicting behavior;
- the decision required from the human Tech Lead.

Do not invent missing business rules.

---

# 2. Engineering authority

The human Tech Lead owns:

- product decisions;
- business-rule approval;
- architecture decisions;
- ADR approval;
- major dependency decisions;
- authentication-model decisions;
- money-model decisions;
- AI trust-boundary decisions;
- final Pull Request review;
- merge decisions.

Claude may autonomously perform implementation work that is already approved.

Claude must request human approval before changing:

- module boundaries;
- architectural style;
- framework strategy;
- major dependencies;
- authentication architecture;
- authorization model;
- Money representation;
- financial calculation semantics;
- AI trust boundaries;
- privacy model;
- approved business rules;
- ADR decisions.

Do not turn an implementation Issue into an architecture redesign.

Approved architectural changes are recorded in a new ADR in `docs/architecture/adr/`.

---

# 3. How to work

Before modifying code:

1. read this file;
2. read the target GitHub Issue when applicable;
3. read relevant BR-xxx rules;
4. read relevant architecture/security/AI documentation;
5. read relevant ADRs;
6. inspect existing implementation;
7. inspect existing tests;
8. understand existing project conventions.

Prefer the smallest coherent change that completely satisfies the requirement.

Do not:

- perform unrelated refactoring;
- introduce speculative abstractions;
- implement future requirements;
- duplicate existing infrastructure;
- weaken existing invariants;
- silently reinterpret requirements.

When a requirement is ambiguous:

STOP and ask for clarification.

Never invent product behavior merely to make implementation easier.

Business-rule identifiers such as `BR-HH-16`, `BR-EXP-*`, `BR-MON-*` and other approved BR identifiers must be referenced in implementation and tests where appropriate.

## 3.1 Commands

Run from `backend/` (JDK 25 toolchain; Docker must be running for tests):

| Task | Command |
|---|---|
| Full build (warnings are errors) + all tests | `./gradlew build` |
| Tests only / one class | `./gradlew test` / `./gradlew test --tests '*ClassName'` |
| Regenerate the committed OpenAPI contract after an API change | `./gradlew updateOpenApi` |
| Run against Docker Compose PostgreSQL (`local` profile) | `docker compose -f ../infra/docker-compose.yml up -d --wait` then `./gradlew bootRun` |
| Run against a throw-away Testcontainers database | `./gradlew bootTestRun` |

---

# 4. GitHub Issue development workflow

Approved implementation work is tracked through GitHub Issues.

One GitHub Issue should normally correspond to one focused Pull Request.

When explicitly asked to implement a GitHub Issue, use the `issue-developer` subagent.

The issue-developer owns the engineering lifecycle from Issue analysis to Pull Request creation.

Workflow:

1. inspect the complete GitHub Issue (`gh issue view <number>`);
2. inspect its dependencies;
3. read relevant documentation;
4. verify the working tree is clean;
5. update `main`;
6. create a dedicated feature/fix branch;
7. implement only the Issue scope;
8. create Liquibase migrations when required;
9. update OpenAPI when required;
10. implement tests;
11. run relevant verification;
12. run the complete affected test/build suite;
13. inspect the complete diff;
14. perform mandatory self-review;
15. fix all BLOCKER and HIGH findings;
16. commit;
17. push the feature/fix branch;
18. create a Pull Request;
19. stop for human review.

Never automatically start the next Issue.

---

## 4.1 Branches

Never implement features directly on `main`.

Before creating a feature branch:

```bash
git status
git checkout main
git pull --ff-only origin main
```

The working tree must be clean.

If unrelated uncommitted changes exist:

STOP.

Do not automatically:

- discard them;
- stash them;
- commit them;
- overwrite them.

Branch naming:

```text
feature/<github-issue-number>-<short-description>
fix/<github-issue-number>-<short-description>
```

Examples:

```text
feature/12-create-expense
feature/18-upload-receipt
fix/42-household-isolation
```

---

## 4.2 Commits

Use Conventional Commits.

Examples:

```text
feat(household): create household (#1)
feat(expense): create manual expense (#12)
fix(receipt): prevent duplicate confirmation (#42)
test(expense): cover household isolation (#12)
```

Reference the GitHub Issue.

Do not include unrelated files.

---

## 4.3 Pull Requests

Pull Requests target `main`.

The PR body must contain:

### Issue

```text
Closes #<issue-number>
```

### Summary

What was implemented.

### Business rules

List relevant `BR-xxx`.

### Technical changes

Important implementation decisions.

### Database

Migrations, constraints and persistence changes.

Use `None` when applicable.

### API / OpenAPI

Endpoints and contracts changed.

Use `None` when applicable.

### Security

Describe:

- authentication impact;
- authorization;
- household isolation;
- personal-data isolation;
- other relevant security considerations.

### Tests

List:

- tests added;
- tests modified;
- verification commands executed;
- results.

### Self-review

At minimum:

```text
BLOCKER: 0
HIGH: 0
```

Document intentional remaining MEDIUM/LOW findings.

### Known limitations

Only genuine limitations remaining within approved scope.

---

## 4.4 Human boundary

Claude may:

- inspect GitHub Issues;
- create feature/fix branches;
- modify implementation;
- create migrations;
- update OpenAPI;
- create tests;
- run tests/builds;
- commit;
- push feature/fix branches;
- create Pull Requests.

Claude MUST NOT:

- push implementation directly to `main`;
- merge Pull Requests;
- force-push `main`;
- delete protected branches;
- modify branch protection;
- disable CI checks;
- bypass required checks;
- merge despite failures.

The human Tech Lead owns final review and merge.

---

## 4.5 Mandatory self-review

Before creating the Pull Request, review the complete diff for:

- business rule violations;
- authorization bypasses;
- household isolation failures;
- security vulnerabilities;
- architecture/module boundary violations;
- transaction problems;
- concurrency/race conditions;
- monetary calculation errors;
- missing database constraints;
- missing tests;
- unnecessary complexity;
- unrelated changes;
- AI trust-boundary violations when applicable.

Classify findings as BLOCKER, HIGH, MEDIUM or LOW.

BLOCKER and HIGH findings must be fixed before creating the PR.

MEDIUM and LOW findings that are intentionally not fixed must be documented in the Pull Request.

---

## 4.6 Failure policy

Never create a Pull Request presented as ready for review when:

- the project does not compile;
- required tests fail;
- integration tests fail;
- architecture tests fail;
- OpenAPI drift exists;
- a known BLOCKER/HIGH issue remains.

Do not bypass, disable, delete, weaken or ignore a failing check merely to complete an Issue.

If completion is blocked, stop implementation and report the blocker.

---

# 5. Backlog management

Approved documentation is transformed into GitHub Issues by the `backlog-manager` subagent.

The backlog-manager may derive technical work strictly required to implement approved requirements.

It must never invent product requirements.

Before creating an Issue it must:

1. inspect approved documentation;
2. inspect existing Issues, including closed Issues;
3. detect duplicates;
4. identify dependencies;
5. identify relevant BR-xxx rules;
6. verify whether behavior already exists;
7. detect missing human decisions.

When documentation is ambiguous or contradictory, the backlog-manager reports:

```text
BACKLOG BLOCKER
```

and does not invent the missing decision.

The backlog-manager does not implement application code.

---

# 6. Architecture

CoupleFinance is a modular monolith.

Follow the approved ADRs, especially the modular-monolith decision.

Primary modules include:

```text
identity
household
expense
categorization
receipt
budget
savings
analytics
insight
assistant
notification
ai
shared
```

Package structure:

```text
com.couplefinance.<module>
├── api/
├── domain/
├── application/
├── infrastructure/
└── web/
```

Responsibilities:

### `api`

Public module contracts available to other modules.

### `domain`

Domain model, invariants, value objects and domain behavior.

### `application`

Use cases and orchestration.

### `infrastructure`

Persistence and external technical adapters.

### `web`

HTTP controllers, request/response DTOs and web concerns.

---

## 6.1 Module boundaries

Inter-module communication is allowed only through:

- `<module>.api`;
- approved domain/application events.

Never access another module's:

- repository;
- JPA entity;
- internal application service;
- implementation package;
- database table directly.

No cross-module JPA relationships.

Use Spring Modulith and ArchUnit tests to enforce module boundaries.

No dependency cycles between modules; use events for reactions.

Never weaken or suppress the Spring Modulith / ArchUnit checks to make a build pass.

---

## 6.2 Business logic

Do not place business logic in:

- controllers;
- repositories;
- mapping code;
- framework configuration.

Controllers translate HTTP concerns.

Application services orchestrate use cases.

Domain objects enforce domain invariants.

Repositories persist and retrieve domain state.

Controllers parse/validate the request DTO, call one application use case and map the result to a response DTO: no calculations, no repository calls, no branching on business state.

Repositories contain no business rules; SQL aggregations are allowed only where the approved design says so (e.g. the spending query API).

---

## 6.3 Events

Use Spring Modulith events according to approved architecture.

Use the transactional outbox/event-publication mechanism where required.

Event listeners must be idempotent and re-read current state rather than trusting the event payload.

Do not perform external network operations while holding database transactions.

This also applies to pooled database connections.

In particular, do not hold a DB transaction while calling:

- AI providers;
- object storage;
- e-mail providers;
- push-notification providers;
- external HTTP APIs.

---

# 7. Java conventions

Backend:

- Java 25;
- Spring Boot;
- Gradle;
- PostgreSQL;
- Liquibase;
- Spring Security;
- Spring Modulith;
- OpenAPI;
- JUnit 5;
- Testcontainers.

Prefer:

- Java records for immutable DTO/value structures;
- constructor injection;
- explicit domain types;
- small cohesive classes;
- deterministic behavior.

Avoid unnecessary inheritance and framework magic.

Do not return `null` from public APIs where `Optional` or an empty collection is appropriate.

---

## 7.1 Time

Use:

- `Instant` for technical timestamps;
- `LocalDate` for business dates.

Never call system time directly in business logic when deterministic testing matters.

Inject `Clock`.

Tests involving time must use a deterministic Clock.

Technical timestamps are stored as `TIMESTAMPTZ`.

"Today" for a household is computed in the household timezone (BR-HH-05).

---

## 7.2 Validation

Use Bean Validation for transport-level validation.

Domain invariants must also be protected by the domain model.

Never assume API validation alone protects domain integrity.

---

## 7.3 Errors

Use RFC 9457 Problem Details according to the approved API conventions.

Errors must have stable machine-readable codes.

Do not expose:

- stack traces;
- SQL;
- internal implementation details;
- existence of inaccessible resources;
- sensitive information.

---

# 8. Money

Money is a critical domain concept.

Never use:

```text
float
double
```

for monetary values.

Use the approved `Money` value object and currency representation.

Currency must always be explicit.

Follow approved BR-MON rules.

Use the approved rounding strategy.

Unless a specific approved business rule says otherwise:

```text
HALF_EVEN
```

Financial calculations must be deterministic.

Do not delegate source-of-truth financial calculations to:

- JavaScript clients;
- LLMs;
- AI providers.

Persist money according to the approved database model, including minor-unit and currency conventions where specified.

Concretely:

- `BigDecimal` for all monetary values, always wrapped in the `Money` value object (amount + currency);
- no `float`, `double`, `Float` or `Double` for amounts, rates applied to money, or percentages computed from money;
- never `new BigDecimal(double)`; never compare with `equals` when scale may differ (use `compareTo`);
- rounding is explicit and applied once, at the end of a computation;
- splitting amounts follows BR-MON-06 (remainder distributed deterministically; parts sum exactly).

Property-based tests should be used for important monetary invariants such as:

- allocation;
- totals;
- rounding;
- line-item sums;
- parsing.

---

# 9. Database

Database:

```text
PostgreSQL
```

Each module owns its persistence model according to the approved database architecture.

Every schema change must use Liquibase.

Never modify an already merged Liquibase changeset.

Create a new changeset.

Conventions:

- one PostgreSQL schema per module; a module only touches its own schema;
- changesets live in the owning module's changelog;
- changeset id: `<module>-<NNNN>-<short-description>`; one logical change per changeset; provide `rollback` when not derivable;
- destructive changes use expand/contract across releases;
- Hibernate `ddl-auto` stays `validate`; never generate schema from entities;
- money columns: `<name>_minor BIGINT` + `currency CHAR(3)`; never `numeric` floats, `double precision` or `money`.

---

## 9.1 Database invariants

When an invariant can be expressed safely in PostgreSQL, enforce it in the database.

Use as appropriate:

- primary keys;
- foreign keys;
- unique constraints;
- partial unique indexes;
- check constraints;
- NOT NULL;
- optimistic locking;
- row locks;
- conditional updates.

Do not protect concurrency-sensitive invariants only with:

```java
if (...) {
    ...
}
```

Application checks may improve errors but do not replace required database guarantees.

Indexes must be justified: every new index states, in its changeset comment, the query or constraint it serves. No speculative indexes. Household-scoped indexes start with `household_id`.

Use `SELECT … FOR UPDATE` / conditional updates where rules require it (member count, receipt confirmation, savings balance); lock multiple rows in a deterministic order.

---

## 9.2 IDs

Use the approved UUIDv7 strategy.

Generate identifiers in the application according to existing project conventions.

Do not introduce a different identifier strategy without approval.

---

## 9.3 Household and personal ownership

Household-owned data must include the appropriate household ownership information.

Personal data must include the appropriate personal ownership information.

Repository access must respect those ownership boundaries.

Every table holding household data has `household_id NOT NULL`; tables that can hold personal data have `owner_user_id`.

Mutable aggregates have a `version` column (optimistic locking).

---

## 9.4 Concurrency

Explicitly consider concurrency for operations involving:

- household membership limits;
- invitation consumption;
- expense invariants where applicable;
- receipt confirmation;
- refresh-token rotation;
- savings balances;
- one-time events;
- idempotency;
- budget alerts.

Use database guarantees rather than timing assumptions.

Add concurrency integration tests when the business rule depends on concurrent correctness.

---

# 10. API

API style:

```text
REST
JSON
/api/v1
```

Use explicit request/response DTOs.

Never expose JPA entities directly.

Request/response DTOs are records in `<module>.web`. JPA entities are never exposed in API responses, events or module facades either.

---

## 10.1 OpenAPI

OpenAPI is generated from code and committed as:

```text
api/openapi.yaml
```

When API behavior changes:

1. update implementation;
2. update/generate OpenAPI;
3. verify drift;
4. commit the synchronized contract.

Use:

```bash
./gradlew updateOpenApi
```

according to repository conventions.

CI must detect OpenAPI drift.

Breaking API changes require explicit human approval.

---

## 10.2 Money JSON

Follow the approved Money JSON representation.

Monetary amounts must not be serialized through floating-point representations.

Format: `{"amount": "12.50", "currency": "EUR"}` — the amount is a **string**.

Monetary aggregates carry their `scope` (`HOUSEHOLD` / `PERSONAL`).

---

## 10.3 Dates

Follow approved conventions for:

- `Instant`;
- `LocalDate`;
- timezone handling.

Do not invent date formats per endpoint.

Dates `YYYY-MM-DD`; instants ISO-8601 UTC.

---

## 10.4 Concurrency contracts

Use approved HTTP concurrency mechanisms where applicable.

For updates requiring optimistic concurrency:

```text
ETag
If-Match
```

Use documented responses such as:

```text
412 Precondition Failed
428 Precondition Required
```

where approved.

---

## 10.5 Idempotency

POST operations requiring idempotency must use the approved:

```text
Idempotency-Key
```

semantics.

Do not create alternative idempotency mechanisms per endpoint.

---

## 10.6 Pagination

Use approved cursor-based pagination.

Do not introduce offset pagination where cursor pagination is required.

Respect approved limits.

Maximum page size: `limit ≤ 100`.

---

# 11. Security

Security is mandatory acceptance criteria, not optional hardening.

Use secure-by-default behavior.

Every endpoint requires authentication unless explicitly listed as public (registration, login, token refresh, password reset, email verification). Deny by default.

---

## 11.1 Identity

User identity comes from the authenticated security principal.

Never trust client-supplied:

- `userId`;
- owner identifiers;
- membership identity;
- authentication state.

Do not allow mass assignment of security-sensitive fields.

---

## 11.2 Household isolation

Household context must be derived from the authenticated user according to the approved household API.

Never use a client-provided `householdId` as proof of authorization.

Every household-owned resource must enforce household isolation.

Repository access should be household-scoped where required.

Use cases receive a `HouseholdContext` resolved by the `household` module; every repository query on household data is scoped by `household_id` (use scoped methods such as `findByIdAndHouseholdId`, never `findById` alone). If a client-supplied id is unavoidable, check it against the caller's `HouseholdContext` before use.

A user must never gain access to another household's:

- expenses;
- receipts;
- budgets;
- categories where private/household scoped;
- savings;
- analytics;
- invitations;
- financial data.

Follow the documented non-disclosure behavior for inaccessible resources.

Where specified, resources from another household must appear as not found rather than revealing their existence.

Another household's resource → **404**, not 403.

---

## 11.3 Personal data

PERSONAL resources must remain visible only to their owner except where an explicitly approved aggregate permits otherwise.

A partner must not obtain another user's personal transaction details through:

- direct APIs;
- analytics;
- error messages;
- filters;
- exports;
- AI;
- indirect identifiers.

Authorization tests are mandatory.

Queries on data that can be personal include `(owner_user_id IS NULL OR owner_user_id = :currentUser)` (BR-EXP-07); personal data must never leak through totals, counts, search, notifications, insights, merchant rules or error messages.

---

## 11.4 Dissolved households

Follow approved lifecycle rules.

Dissolved households are read-only where documented.

Do not accidentally allow mutation through secondary endpoints.

Background jobs must also respect lifecycle state.

Background jobs use `SystemHouseholdContext` built from the stored `household_id` — never unscoped queries, except in dedicated purge classes.

---

## 11.5 Logs

Never log secrets.

Avoid logging sensitive combinations such as:

- financial details + identity;
- receipt content;
- access tokens;
- refresh tokens;
- authentication credentials;
- presigned URLs;
- raw AI payloads containing sensitive information unless explicitly approved and protected.

Log pseudonymous ids only; never log amounts together with merchant and user.

---

## 11.6 Receipt storage

Receipt documents are private.

Use approved private object-storage mechanisms.

Presigned URLs must:

- be short-lived;
- be issued only after authorization;
- never become permanent public URLs.

File uploads must follow approved validation and security requirements.

---

## 11.7 Secrets

No secrets committed: no keys, passwords, tokens or credentials in code, config, tests or fixtures. Use environment variables / a secret manager; local defaults only for Docker Compose development services.

---

# 12. AI trust model

AI output is untrusted input.

This rule is fundamental.

Never allow an LLM or AI provider to directly persist authoritative financial data.

---

## 12.1 Receipt analysis

Expected flow:

```text
receipt upload
    ↓
private storage
    ↓
AI extraction
    ↓
structured schema
    ↓
schema validation
    ↓
deterministic business validation
    ↓
user review
    ↓
explicit confirmation
    ↓
Expense persistence
```

The AI must not bypass user confirmation where confirmation is required.

AI results are stored only as drafts until the user confirms them (BR-RCP-02, BR-RCP-10). AI adapters return DTOs; only business use cases persist, after validation. The `ai` module has no access to business tables.

---

## 12.2 Structured output

Use structured output according to approved schemas.

Validate:

- schema;
- required fields;
- types;
- currency;
- dates;
- monetary consistency;
- category validity;
- confidence ranges;
- line totals;
- receipt total.

Allow at most the approved repair/retry behavior.

At most one repair retry; output that does not match the schema is a failure, never "best effort" parsed. Deterministic validation rules: BR-RCP-03 … BR-RCP-14. Never auto-correct a value to make it fit; surface ambiguity to the user.

Never silently guess ambiguous financial values.

---

## 12.3 Deterministic calculations

AI may explain financial facts.

AI must not be the source of truth for calculations such as:

- totals;
- averages;
- budget consumption;
- remaining budget;
- percentages;
- month-over-month changes;
- savings progression;
- category aggregation.

Compute these deterministically using Java/SQL.

AI receives structured calculated facts when generating explanations.

No LLM computes, rounds, converts or aggregates money (BR-MON-08). Insight text uses placeholders; values are injected by the server (BR-AI-10).

---

## 12.4 AI abstraction

Business modules depend on:

```text
ai.api
```

Do not couple business logic directly to a vendor SDK.

Vendor-specific code belongs in the approved AI infrastructure layer.

Every AI call is logged with metadata only (provider, model, prompt version), never content. Prompts are versioned files; changing a prompt or model requires running the evaluation dataset.

---

## 12.5 Consent and privacy

Before sending data to an AI provider:

- verify required consent;
- send only necessary data;
- respect documented purpose limitation;
- respect retention requirements;
- respect privacy requirements.

Consent must be checked according to the approved design, not inferred.

Check the relevant user's consent before any provider call (BR-AI-05); send only minimal data (BR-AI-06).

---

# 13. Testing

- **Unit tests for business rules**: every rule implemented gets tests named after it (e.g. `BR_EXP_08_expense_items_must_sum_to_amount`). Money, expense items and parsers also get property-based tests.
- **Integration tests for repositories and the API** against a real database.
- **Testcontainers for PostgreSQL** — no H2 or other in-memory substitute. Liquibase migrations run in tests.
- **Authorization tests are mandatory** for every endpoint touching household data:
  - a user of another household gets 404;
  - the partner cannot observe a PERSONAL resource (directly or via aggregates/search);
  - unauthenticated access is rejected;
  - write attempts on a dissolved household are rejected.
- Concurrency-sensitive rules get concurrency tests (receipt confirmation, member count, budget alerts, idempotency).
- AI adapters are tested with recorded provider responses (WireMock); business logic is tested with the fake AI adapter. Tests never call real AI providers.
- Architecture tests (Spring Modulith, ArchUnit) must stay green.
- Tests are deterministic: fixed `Clock`, no dependence on execution order or wall time.

Integration tests use `@IntegrationTest` (`com.couplefinance.support`): one cached Spring context + one PostgreSQL container for the whole suite — reuse it rather than adding new context configurations. Use `TestUsers` to seed accounts and `TestTokens` to send real signed access tokens (never bypass token validation in integration tests). Tests share the database: create fresh users/households per test and scope assertions to them.

---

# 14. Mobile (React Native / TypeScript)

- TypeScript `strict`; no `any` without justification.
- API client is **generated** from `api/openapi.yaml`; never hand-write API types.
- No money arithmetic or aggregation on device beyond input handling; amounts stay strings / integer minor units. All totals come from the backend.
- Tokens: refresh token in secure storage only; never in AsyncStorage or logs.
- Client-side validation is UX only; the server is authoritative.

---

# 15. Definition of Done

A change is done only when **all** of the following hold:

- [ ] **Implementation** complete and consistent with `docs/` and the relevant `BR-xxx` rules.
- [ ] **Tests** added/updated: unit tests for rules, integration tests for repositories/API, authorization tests for household resources.
- [ ] **Migrations**: Liquibase changesets for any schema change, with DB constraints and justified indexes.
- [ ] **OpenAPI** spec regenerated and committed for any API change.
- [ ] **Documentation** updated when behaviour, rules, architecture or decisions change (business rules, docs, new ADR if applicable).
- [ ] **All checks green**: build, tests (including Testcontainers), architecture tests, OpenAPI diff, linters. Never skip, disable or delete a failing test or check to get green — fix the cause or ask.
- [ ] No secrets, no debug leftovers, no unrelated changes in the diff.

When reporting completion, state what was verified and how; if something could not be run, say so explicitly.
## Autonomous development orchestration

Autonomous backlog development is orchestrated **outside Claude** by the
PowerShell script `scripts/autonomous-development.ps1`.

```powershell
.\scripts\autonomous-development.ps1 -DryRun        # analysis only, changes nothing
.\scripts\autonomous-development.ps1 -MaxIssues 1   # one fresh Claude process, one PR
```

Claude sessions must not orchestrate the backlog themselves: they never
select, chain or start further Issues. The `development-orchestrator`
subagent is superseded by the external script and must not be used.

### Fresh-context execution

Autonomous backlog development uses one fresh Claude Code process per
GitHub Issue.

Each Issue implementation must run in an independent Claude Code context.

The Issue developer must not rely on conversation context from previous
Issues.

All required context must be reconstructed from durable project sources:

- CLAUDE.md
- the GitHub Issue
- referenced BR-xxx rules
- relevant ADRs
- relevant documentation
- current code
- current tests

Do not load all documentation into every context: read CLAUDE.md, the
target Issue, its referenced rules and documents, and the relevant
code/tests. One development context covers the whole Issue lifecycle; do
not split one Issue into several fresh contexts per layer.

The external development orchestrator is responsible for selecting the
next READY Issue and starting a new Claude Code process.

One Issue = one development context = one feature/fix branch = one Pull
Request.

Dependent Issues must not start until their prerequisite implementation
has been merged into main.

Independent READY Issues may be implemented while other Pull Requests
wait for human review.

Claude must never merge Pull Requests automatically.

### READY Issues

An Issue is eligible for autonomous implementation only when ALL hold:

1. it is OPEN;
2. it represents approved implementation work;
3. it carries the `status:ready` label;
4. it does not carry the `status:blocked` label;
5. its acceptance criteria are sufficiently defined;
6. required human product/architecture decisions are resolved (no
   unresolved `## Open question` section);
7. no active Pull Request already implements it;
8. every implementation dependency listed in its `## Dependencies`
   section has a Pull Request MERGED into `main`.

A dependency is not satisfied merely because work started, a branch or PR
exists, the Issue was closed, or Claude says implementation is complete.

Dependencies are declared in the Issue body:

```md
## Dependencies

None
```

or bullet lines referencing real Issue numbers, e.g. `- HOUSEHOLD-002 (#8)`.
Non-bullet notes in that section are informational, not blocking.

Selection order among eligible Issues: dependency order (Issues unblocking
the most open work first), then `priority:high` / `priority:medium` /
`priority:low`, then GitHub Issue number as final tie-breaker. Execution
is sequential: one implementation process at a time unless the human
explicitly authorizes parallel runs.

### Review loop

Each Issue's diff is reviewed, before commit, by the independent
read-only `code-reviewer` and `security-reviewer` subagents in addition to
the issue-developer's mandatory self-review (§4.5). Valid BLOCKER/HIGH
findings are fixed and re-verified; reviewer suggestions that conflict
with approved rules, ADRs, the security model or the Issue scope require a
human decision and stop the Issue.

### Stop conditions

The autonomous loop stops when:

- no READY Issue remains;
- human input is required;
- a blocking failure occurs;
- continuing would be unsafe.

The orchestrator and all agents must never merge Pull Requests, enable
auto-merge, approve their own Pull Requests, push to `main`, force-push,
build dependent work on unmerged feature branches, invent missing
requirements, or bypass branch protection, CI or required checks.

Human review and merge remain mandatory.
