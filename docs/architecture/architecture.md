# CoupleFinance — Architecture

Status: **Draft v0.3** — 2026-09-28 (v0.2: Staff Engineer review, see §13; v0.3: domain model & schema, ADR-006)

Related: [security.md](security.md) · [ai.md](ai.md) · [database.md](database.md) ·
[business-rules.md](../product/business-rules.md) · ADRs:
[001](adr/001-modular-monolith.md) · [002](adr/002-household-lifecycle-and-data-ownership.md) ·
[003](adr/003-expense-model.md) · [004](adr/004-ai-output-trust-model.md) ·
[005](adr/005-ai-consent-and-data-protection.md) · [006](adr/006-domain-model-and-persistence.md) ·
[domain-model.md](domain-model.md) · [database-schema.md](database-schema.md)

---

## 1. Goals and quality attributes

| Attribute | Target / meaning |
|---|---|
| **Correctness of money** | Exact decimal arithmetic, deterministic results, no AI in calculations (BR-MON-08). Highest priority. |
| **Data isolation** | No access across households; no access to a partner's personal data; no access by future partners to former partners' data. |
| **Evolvability** | Features added module by module; modules extractable later. |
| **Provider independence** | AI provider, object storage and email provider are replaceable adapters. |
| **Testability** | Each module testable in isolation; integration tests against real PostgreSQL (Testcontainers). |
| **Performance** | p95 < 300 ms for CRUD and analytics at 10k households; receipt analysis asynchronous, p95 < 20 s end-to-end. |
| **Operability** | Structured logs, metrics, traces; one deployable unit. |

## 2. System context

```
                ┌──────────────────────┐
  Partner A ───▶│                      │
                │  CoupleFinance app   │  (React Native, iOS + Android)
  Partner B ───▶│                      │
                └──────────┬───────────┘
                           │ HTTPS / REST + JSON (OpenAPI)
                           ▼
                ┌──────────────────────┐        ┌────────────────────────┐
                │ CoupleFinance API    │───────▶│ AI provider(s)         │ (LLM / vision / OCR)
                │ Spring Boot modular  │        └────────────────────────┘
                │ monolith (Java 25)   │        ┌────────────────────────┐
                │                      │───────▶│ Object storage (S3 API)│ receipt images
                │                      │        └────────────────────────┘
                │                      │        ┌────────────────────────┐
                │                      │───────▶│ Email provider         │
                │                      │        └────────────────────────┘
                │                      │        ┌────────────────────────┐
                │                      │───────▶│ Push (APNs / FCM)      │ V1
                └──────────┬───────────┘        └────────────────────────┘
                           ▼
                ┌──────────────────────┐
                │ PostgreSQL           │  (schema per module, Liquibase)
                └──────────────────────┘
```

## 3. Architectural style

- **Modular monolith** — one Spring Boot application, one deployable, one database, strong internal module
  boundaries ([ADR-001](adr/001-modular-monolith.md)).
- **Domain-oriented modules** following business capabilities.
- **Hexagonal inside each module** where there is real domain logic.
- **Boundaries enforced by tooling** — Spring Modulith `ApplicationModules.verify()` + ArchUnit; violations fail CI.
- **REST API**, JSON, OpenAPI 3.1.

## 4. Modules

| Module | Responsibility | Owns (schema) | Depends on (API only) |
|---|---|---|---|
| `shared` (kernel) | `Money`, `CurrencyCode`, ids, `BudgetPeriod`, clock, error model, `HouseholdContext` types. No persistence. | — | — |
| `identity` | Registration, credentials, sessions & tokens, password reset, account deletion (tombstones), **per-user AI consents**, personal data export. | `identity` | shared |
| `household` | Household, members, invitations, **join requests & approval**, settings, **budget period calendar**, **dissolution & archive access**, **merge orchestration**. Provides `HouseholdContext`. | `household` | identity; orchestrates merge via APIs of categorization, expense, receipt, savings, budget |
| `expense` | Ledger: kinds (expense/refund/transfer), **expense items**, sharing & **visibility**, audit, idempotency, restore, search, **spending query API** (BR-SCP). | `expense` | household, categorization |
| `categorization` | Categories (archive-only), merchant normalisation (versioned), household & user merchant rules, suggestion (rules → AI). | `categorization` | household, identity (consent), ai |
| `receipt` | Evidence: multi-page upload, storage, analysis jobs, extraction pipeline, parsing & ambiguity, validation, drafts, review contract, exactly-once confirmation, attach-to-expense, duplicates. | `receipt` | household, identity (consent), expense, categorization, ai, storage port |
| `budget` | Budgets per period, category limits, consumption & status, exactly-once alerts. | `budget` | household, expense (query API) |
| `savings` (V1) | Goals, movements, progress, required amount, overdue. | `savings` | household |
| `analytics` | Deterministic aggregations on household and personal scopes; **facts** (with qualitative fields) for insights and assistant. Read-only. | `analytics` (optional projections) | household, expense, budget, savings |
| `insight` (V1) | Insight selection, constrained wording via `ai`, verification, per-locale rendering, feedback. | `insight` | analytics, ai, identity (consent), household |
| `assistant` (V2) | Conversation orchestration with read-only tools. | `assistant` | analytics, ai, identity (consent) |
| `notification` (V1) | Push tokens, preferences, notifications reacting to events (privacy rules BR-NOT). | `notification` | household |
| `ai` | AI ports and provider adapters, prompt registry, consent guard, resilience, bounded concurrency, call logging. **No business rules.** | `ai` | shared, identity (consent guard) |

Dependency rules:

1. A module accesses another only through its **public API package** (`<module>.api`).
2. **No cross-module repository access, no cross-module JPA relationships**; references by id.
3. **No cycles.** Reactions via domain events.
4. `ai` never depends on business modules; business modules never import provider SDKs.

### 4.1 Module internal structure

```
com.couplefinance.<module>
├── api/            # public: facades, DTOs, events
├── domain/         # entities, value objects, domain services, repository ports
├── application/    # use cases (transaction boundary, authorisation, orchestration)
├── infrastructure/ # persistence and external adapters, Liquibase changelog
└── web/            # REST controllers, request/response models
```

## 5. Inter-module communication

- **Synchronous** calls through `api` facades for immediate answers.
- **Domain events**: `ExpenseCreated/Updated/Deleted/Restored`, `ReceiptReadyForReview`, `BudgetStatusReached`,
  `MemberJoined`, `HouseholdDissolved`, `HouseholdMerged`, `ConsentChanged`.
- Published via Spring Modulith's transactional event publication registry (PostgreSQL outbox), delivered after
  commit with retry. **Listeners are idempotent and re-read current state** (events may arrive out of order,
  e.g. an update processed before a create's listener) rather than trusting event payloads for decisions.
- No broker in MVP; externalisable later.

## 6. Key flows

### 6.1 Manual expense

```
App ─POST /expenses (Idempotency-Key)─▶ expense.web
   → claim idempotency key (insert first; BR-EXP-13)
   → HouseholdContext (ACTIVE household, member) → validate BR-MON, BR-EXP (kind, items, visibility)
   → categories active (categorization.api)
   → persist expense + items + audit + ExpenseCreated (one transaction; Σ items checked again at commit)
   ◀ 201 Created (ETag: version)
after commit: categorization updates correction counters / rules (BR-CAT-05, household vs user scope)
              budget re-evaluates status → insert-or-skip budget_alert → BudgetStatusReached → notification
```

### 6.2 Receipt analysis (asynchronous)

```
1. App POST /receipts (1–5 files)       → verify types/sizes, re-encode, hashes, store pages → status=OPEN
                                           → consent? analysis_status=PENDING + job : SKIPPED_NO_CONSENT
                                                                                         ◀ 202 {receiptId}
2. Worker: tx#1 claim job (FOR UPDATE SKIP LOCKED), analysis_status=RUNNING, commit
           (no transaction, no DB connection held)
           redaction → consent re-check → ai.ReceiptExtractor (bounded concurrency, timeout)
           tx#2 store raw output, parse (ambiguity), validate, duplicates, suggest categories,
                write draft, analysis_status=SUCCEEDED — only if status=OPEN AND analysis_status=RUNNING
                (else the user confirmed meanwhile: result discarded), ReceiptReadyForReview, commit
3. App GET /receipts/{id} (poll or push) → draft visible to uploader only
4. App POST /receipts/{id}/confirm {values, resolutions, acknowledgedTotal, acknowledgedDate, version}
        + Idempotency-Key
     → conditional update status OPEN→CONFIRMED, running analysis → CANCELLED
       (fails → 409 RECEIPT_ALREADY_CONFIRMED); insert receipt_confirmation snapshot
     → re-validate as manual expense → expense.api.createExpense(..., receiptId)  [UNIQUE receipt_id]
     → same transaction
   Confirmation is allowed from any OPEN receipt, whatever its analysis status (manual entry).
   or POST /receipts/{id}/attach {expenseId} → status=ATTACHED (expense.receipt_id set via expense API)
```

Failures: retries with backoff on transient errors (max 3) → `FAILED`; manual retry ≤ 3 (BR-RCP-17).
Circuit breaker per provider; uploads still accepted and queued.

**Rule: no database transaction or pooled connection is held during outbound calls** (AI, storage, email).
Virtual threads make blocking cheap, but the connection pool is not.

### 6.3 Insight generation (V1)

```
Scheduler (daily per household, + at period close) — SystemHouseholdContext
  → analytics computes household-scope Facts (values + deterministic direction/driver)
  → insight rules select candidates
  → if both members consented: ai.InsightWriter (no values sent) → verify placeholders, digits, polarity, causality
    else / on failure: fixed per-locale template
  → store facts + per-locale template; render values per reading user's locale on GET
```

### 6.4 Household lifecycle

```
Invite:    member A creates invitation (code shown once)
Join:      user B redeems code → join_request PENDING (+ merge mode if B owns a solo household)
Approve:   A approves (recent auth) → tx: lock households (ordered) → member count check →
           [merge per BR-HH-13 via module APIs] → membership → MemberJoined / HouseholdMerged
Dissolve:  either member (recent auth) → status=DISSOLVED, archive access 90 days for both,
           HouseholdDissolved → notifications; each may create a new household seeded per BR-HH-11
Purge:     scheduler deletes dissolved households after purge_at
```

## 7. API design

- REST over HTTPS, JSON, base path `/api/v1`.
- **OpenAPI 3.1** generated from code (springdoc), committed to `api/openapi.yaml`, diffed in CI; breaking-change
  check (e.g. `oasdiff`) against the last release; TypeScript client generated from it.
- Resources: `POST /households` (create), `/households/me`, `/households/me/invitations`, `/households/me/join-requests`,
  `/households/me/dissolution`, `/expenses`, `/receipts`, `/categories`, `/budgets/{periodStart}`,
  `/savings-goals`, `/analytics/periods/{periodStart}?scope=HOUSEHOLD|PERSONAL`, `/insights`, `/me/consents`.
  The household is **derived from the authenticated user**.
- Money: `{"amount": "12.50", "currency": "EUR"}` (string amount).
- Dates `YYYY-MM-DD`; instants ISO-8601 UTC; periods identified by `periodStart` date.
- Every monetary aggregate in responses carries `scope` (BR-SCP-03).
- Errors: RFC 9457 Problem Details with stable `code` and field `errors[]`.
- Pagination: cursor-based, `limit ≤ 100` (`cursor`, `limit` query parameters; default 20, values above 100 are capped to 100, below 1 → 400 `INVALID_LIMIT`; response `{items, nextCursor}`, `nextCursor` null on the last page). Cursors are opaque, AES-GCM encrypted and bound to the caller and listing; invalid/foreign cursor → 400 `INVALID_CURSOR`.
- Rate limiting: Bucket4j in-memory, per IP (before authentication) and per user, profile-based and configurable (`couplefinance.rate-limit.*`); exceeded → 429 `RATE_LIMITED` with `Retry-After`. All responses carry `Cache-Control: no-store`.
- Concurrency: `ETag`/`If-Match`; stale → **412** `VERSION_CONFLICT`, missing → **428** `IF_MATCH_REQUIRED`, malformed → **400** `IF_MATCH_INVALID` (BR-EXP-12). Business-state conflicts
  (e.g. receipt already confirmed, household full) → **409** with specific code.
- Idempotency: `Idempotency-Key` on creating POSTs and on receipt confirmation (BR-EXP-13).

## 8. Backend technology

| Concern | Choice |
|---|---|
| Language / runtime | Java 25; virtual threads for request handling and outbound calls. |
| Framework | Spring Boot (4.x line), Web MVC, Security, Spring Modulith. |
| Persistence | Spring Data JPA for aggregates; `JdbcClient` SQL for spending queries. |
| Migrations | Liquibase, one changelog per module. |
| Validation | Bean Validation at the edge + domain validation. |
| Money | Own `Money` value object (`BigDecimal` + currency) ↔ `BIGINT` minor units. |
| Resilience | Resilience4j (timeouts, retry, circuit breaker, **bulkhead** for AI concurrency, rate limiter). |
| Jobs | DB-backed queue (`SKIP LOCKED`) + scheduling; ShedLock for singleton schedules. |
| API docs | springdoc-openapi. |
| Observability | Micrometer, OpenTelemetry, JSON logs, Prometheus. |
| Testing | JUnit 5, AssertJ, Testcontainers (PostgreSQL, MinIO), WireMock, Spring Modulith tests, ArchUnit, jqwik (property-based tests for `Money`, expense items, parsers). |
| Build | Gradle (Kotlin DSL) **[proposal]**. |

### Testing strategy

| Level | Scope |
|---|---|
| Unit | Domain objects, `Money`, expense items (Σ invariant), refund limits, receipt parser (ambiguity matrix per locale), consistency checks, budget/savings computations. Every numeric BR has tests. |
| Module | `@ApplicationModuleTest` per module with PostgreSQL Testcontainer. |
| API | Full app with Testcontainers against the OpenAPI contract. |
| Authorisation | Generated cross-household matrix (404) and **partner-privacy matrix** (PERSONAL data never observable by partner, including via totals, search, counts, errors); dissolved-household read-only tests. |
| Concurrency | Parallel receipt confirmations (exactly one expense), parallel joins (max 2 members), parallel budget alerts (one notification), idempotency races, parallel refresh-token use. |
| Transaction boundaries | Test asserting no open transaction/connection during AI adapter calls. |
| AI | WireMock-recorded provider responses; golden-dataset evaluation (ai.md §9). |
| Architecture | `ApplicationModules.verify()`; ArchUnit: no provider SDK outside `ai.infrastructure`, no `double`/`float` in money code, scoped repositories only. |

## 9. Mobile architecture (React Native + TypeScript)

| Concern | Choice |
|---|---|
| Framework | React Native with **Expo** (managed + dev builds) **[proposal]**; TypeScript strict. |
| Navigation | React Navigation (or Expo Router). |
| Server state | TanStack Query (cache, retries, optimistic updates). |
| API client | Generated from `openapi.yaml`; single-flight token refresh interceptor. |
| Local state | Minimal (Zustand/context) for UI only. |
| Forms | React Hook Form + Zod (UX only). |
| Money | Strings / minor-unit integers in a small `money` utility; `Intl.NumberFormat` formatting; no float arithmetic, no aggregation. Allocation editor uses integer minor units. |
| Secure storage | `expo-secure-store` for refresh token. |
| Camera | Multi-shot capture for long receipts; client-side resize and compression. |
| Review UI | Field status badges, candidate pickers for `AMBIGUOUS`, explicit total/date acknowledgement. |
| Charts | Native-rendering chart library fed by analytics endpoints. |
| i18n | From day one; per-user locale (partners may differ). |
| Testing | Jest + RN Testing Library; Maestro or Detox E2E. |
| Structure | Feature folders mirroring backend modules. |

## 10. Deployment & operations

- One container image; stateless; horizontal scaling (jobs use DB locking). Receipt workers can run as a
  separate deployment of the same image (profile) if they compete with API traffic.
- Managed PostgreSQL (PITR), S3-compatible storage with SSE and lifecycle rules (cold storage for old images,
  BR-RCP-20).
- Environments: local (Docker Compose), staging, production. Liquibase as a separate pre-deploy step in production.
- EU hosting region **[assumption]**.
- CI: build, tests, architecture checks, OpenAPI diff, dependency/container scans.

## 11. Cross-cutting decisions summary

| # | Decision | Where |
|---|---|---|
| D1 | Modular monolith with enforced boundaries | ADR-001 |
| D2 | Money: `BigDecimal` / `BIGINT` minor units / string in JSON; single household currency; per-currency max | database.md, BR-MON |
| D3 | AI behind ports in `ai`; no provider SDKs in business modules | ai.md |
| D4 | AI output = draft; ambiguity surfaced; deterministic checks; mandatory acknowledgement of total/date | ADR-004 |
| D5 | Insights: placeholders + deterministic direction/driver + polarity verification; per-locale rendering | ADR-004 |
| D6 | Household derived from token; scoped queries; `household_id` on all tables; RLS for GA | security.md |
| D7 | Async receipt pipeline, DB-backed jobs, outbox; no transaction held during outbound calls | §5, §6.2 |
| D8 | Schema per module in one database | database.md |
| D9 | Code-first OpenAPI, committed, diffed, TS client generated | §7 |
| D10 | Household lifecycle: approval-based join, merge, unilateral dissolution, read-only archive, no inherited history | ADR-002 |
| D11 | Personal expenses private to their owner; single spending-scope definition | ADR-002, ADR-003 |
| D12 | Expense kinds (expense/refund/transfer) and multi-category expense items | ADR-003, ADR-006 |
| D13 | Per-user explicit AI consent, DPIA, minimisation, redaction step | ADR-005 |
| D14 | Explicit budget periods stored with start/end dates | BR-HH-07, database-schema.md |
| D15 | Receipt = evidence, Expense = financial data; single link `expense.receipt_id` | ADR-006 |
| D16 | Database-level invariants: composite household FKs, currency pinning, member seats, Σ items trigger | ADR-006, database-schema.md |
| D17 | Receipt `status` and `analysis_status` as separate dimensions | ADR-006 |

## 12. Open questions

Resolved by this revision: budget scope, personal visibility (ADR-002/003). Remaining:

1. **Partner balance / settle-up** and custom split ratios (transfers are recorded; balance computation and
   `expense_share` table deferred) — decide before V1.
2. **Income** tracking (savings-rate insights).
3. **Multi-currency** (travel): conversion source, rates, rounding.
4. **Authentication**: in-house vs external IdP; Apple/Google sign-in.
5. **AI provider(s)**, DPA, EU residency, cost ceiling; vision LLM vs OCR vs hybrid.
6. **DPIA outcome**: redaction pre-processor mandatory at MVP?
7. **Offline capture** requirements.
8. **Expo vs bare React Native**.
9. **Launch languages** (app, receipts, insights).
10. **Recurring expenses** — MVP or later?
11. **Monetisation** (affects AI quotas).
12. **Period start day ≠ 1** at MVP, or later?
13. **Retention periods** legal validation (BR-DAT).
14. **RLS** adoption timing and performance.
15. **Personal budgets** — needed with private personal expenses?

## 13. Review findings resolution

Staff Engineer review of v0.1 (2026-09-28). All findings addressed in v0.2:

| Finding | Resolution |
|---|---|
| C1 New partner inherits ex's history | ADR-002; BR-HH-09 … BR-HH-11 |
| C2 Health data / consent model | ADR-005; BR-AI-05, BR-AI-08; ai.md §7 |
| C3 Ambiguous amount/date parsing | ADR-004; BR-RCP-05; ai.md §4.3 |
| H1 VAT-inclusive totals, negative lines, uncorroborated totals | BR-RCP-04, BR-RCP-06, BR-RCP-07; ai.md §4.4 |
| H2 Automation bias / metrics | BR-RCP-10, BR-RCP-11; vision.md §6; ai.md §8–9 |
| H3 Insight semantic correctness | BR-AI-10; ai.md §5.2 |
| H4 Budget vs analytics scope | ADR-003; BR-SCP |
| H5 Partner removal / coercive control | ADR-002; BR-HH-09, BR-EXP-07; security.md §4.2 |
| H6 Invitation security | BR-HH-04; security.md §4.3 |
| H7 Double confirmation of receipt | BR-RCP-15; database.md §2.4–2.5; §6.2 |
| H8 Onboarding merge | BR-HH-13, BR-HH-14; database.md §4 |
| H9 Account deletion contradictions | BR-DAT; database.md §2.1 |
| M1 Refunds / transfers | ADR-003; BR-EXP-02 … BR-EXP-04 |
| M2 Multi-category receipts | ADR-003; BR-EXP-08 |
| M3 Receipt edge cases | BR-RCP-01, BR-RCP-12, BR-RCP-13, BR-RCP-17 |
| M4 Duplicate detection | BR-RCP-14 |
| M5 System execution context | security.md §4.4 |
| M6 Connection pool during AI calls | §6.2; database.md §6 |
| M7 Refresh-token race | security.md §3 |
| M8 Budget alert uniqueness / category deletion race | BR-BUD-06, BR-CAT-03; database.md §2.6 |
| M9 Period start/timezone changes, tracking start | BR-HH-05, BR-HH-07, BR-HH-08, BR-ANA-03 |
| M10 Per-locale insights | ai.md §5.2; database.md §2.8 |
| M11 Merchant rule learning | BR-CAT-04, BR-CAT-05 |
| M12 `household_id` on all tables | database.md §1 |
| M13 Golden dataset privacy | BR-AI-09; ai.md §9 |
| L1 Access-token revocation window | security.md §3 |
| L2 Lock-screen amounts | BR-NOT-01 |
| L3 Assistant injection via notes | ai.md §6 |
| L4 Per-currency max amount | BR-MON-07 |
| L5 Idempotency semantics | BR-EXP-13 |
| L6 Rounding references, overdue goals | BR-MON-05, BR-MON-09, BR-SAV-04 |
| L7 409 vs 412 | §7; BR-EXP-12 |
| L8 Restore deleted expenses | BR-EXP-11 |
| L9 Storage/log growth, quota abuse, DB roles | BR-RCP-20; database.md §1, §6; ai.md §8 |
| L10 Two-member assumption | BR-HH-01 (list model) |
| L11 Timezone wording | BR-HH-05 |
