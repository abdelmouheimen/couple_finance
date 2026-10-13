# ADR-009 — Household join journey: lifecycle participants, recent authentication, archive access and end of the source household

- **Status:** Accepted
- **Date:** 2026-10-13 (decided by the Tech Lead; recorded with DOC-005, issue #113)
- **Related:** ADR-001 (modular monolith), ADR-002 (household lifecycle), ADR-005 (consent), ADR-007 (managed
  authentication); business-rules.md BR-HH-04, BR-HH-09 … BR-HH-14, BR-HH-17 … BR-HH-19; DOC-006 (issue #118) for the
  realisation of decision D12

## Context

ADR-002 decided approval-based join, merge (BR-HH-13), unilateral dissolution and read-only archives. Preparing the
implementation exposed gaps that ADR-002 does not settle:

1. **Module cycle.** database.md §4 had `household` call `categorization.remap`, `expense.moveHousehold`,
   `receipt.moveHousehold` and `budget.discard`, and database-schema.md §12.4 had it purge each module. But `household`
   is declared with `allowedDependencies = {"shared", "identity :: api"}` and every business module depends on
   `household.api` (`HouseholdContext`). Direct calls would create cycles that Spring Modulith rejects (CLAUDE.md §6.1).
2. **"Recent authentication"** (security.md §4.3, architecture.md §6.4) was never defined; security.md §3 only required a
   non-revoked `sid`.
3. **Archive unreachable after a join.** Resolution of the caller's household takes the active membership first; a user
   who joined T could not reach the archive of S.
4. **Lifecycle races.** A write checks `HouseholdContext.status` without a lock; a dissolution or merge committing in
   between could let a write land in a dissolved household.
5. **End of the source household S.** BR-HH-13 said "exported, then deleted with S" and "S is deleted afterwards"
   without defining when, who can still read S, and what happens to the S budgets and the data not brought.
6. **Join request lifecycle.** States, validity, consumption of the code and who may decide were not specified.

## Decision

Numbers A1 and D1 … D20 refer to the decision register of issue #113. They are **local to this ADR** and unrelated to
the architecture decision log D1 … D18 of architecture.md §11 (where this whole ADR is entry D18). Items not listed here (D2, D3, D4, D5, D10,
D11, D14, D16, D18, D19, D20) are recorded in the business rules and documents named in "Where recorded" below.

### 1. Lifecycle participants (A1, D14) — dependency inversion

`household.api` defines two small, separate SPIs, each in its own sub-package so that `household.api` does not become
a catch-all:

- `household.api.merge.HouseholdMergeParticipant` — one method: move the joiner's data of S to T, inside the caller's
  transaction; returns a small result record. Runs **inside** the approval transaction (`Propagation.MANDATORY`),
  performs only scoped writes in the participant's own schema and makes **no network call**. The request carries
  source, target, joiner, mode and the results of earlier participants (category id map, moved expense ids).
- `household.api.purge.HouseholdPurgeParticipant` — one method: purge a household's data under
  `SystemHouseholdContext`. Idempotent and retryable; may manage its own short transactions and perform external
  calls (object storage) outside any transaction.

Each business module implements the SPIs in its own package with its own repositories; `household` orchestrates them in
a fixed, documented order and never sees another module's types.

- Merge order: `categorization` → `expense` → `receipt` → (`savings`, V1).
- Purge order (database-schema.md §12.4): `receipt` → `expense` → `budget` → (`savings`) → `categorization` →
  `household`.
- `budget` takes no part in the merge: S budgets are never transferred and are purged with S (BR-HH-13).
- The receipt purge participant deletes storage objects outside any transaction.
- The future `savings` module (V1) joins both orders by implementing the same two interfaces; no savings work is part of
  the join journey (D14).
- Spring Modulith `verify()` must show no cycle.

### 2. Recent authentication for sensitive operations (D6)

A sensitive operation requires:

1. the token's `sid` is an existing session of the caller (`sub`), not revoked and not expired; **and**
2. that session was created by a credential login less than `couplefinance.security.recent-auth-window` ago
   (default `PT15M`, configurable).

`identity.session.created_at` is the instant of the last effective authentication for that session: login verifies the
password; a token refresh never changes it. Re-authentication is an explicit login, which creates a new session; the
mobile app then revokes the superseded session (existing logout). No new credential flow: compatible with ADR-007 and
with future passkeys / social sign-in.

Errors: **401** `SESSION_REVOKED`; **403** `REAUTHENTICATION_REQUIRED` (403, not 401, so the mobile single-flight
refresh does not loop).

Sensitive operations: join approval; archive export and archive document download; redemption by a requester who owns
an `ACTIVE` household (approval will dissolve or merge it); dissolution; single-member household deletion; account
deletion. (Consent changes join this list when implemented.)

### 3. Archive access (D7)

- Explicit routes `/api/v1/archives` and `/api/v1/archives/{archiveId}/...`. `household.api` resolves an
  `ARCHIVE_READER` context only if the caller has a membership of that household with `left_at` set and
  `archive_access_until > now`; otherwise **404**. The end date is returned and displayed.
- Reads reuse the existing use cases; no write route exists. Archive routes never replace or alter the resolution of the
  caller's active household.
- **CSV export** of expenses, with formula-injection protection.
- **Documents and receipts**: retrievable per page through 5-minute presigned URLs (HOUSEHOLD-011) and as a bulk ZIP
  (RECEIPT-007). Original PDFs are never served back (security.md §6): the re-encoded page images are what is
  retrievable.
- Who may read: former members of a household left by dissolution (BR-HH-10, BR-HH-12) — and after a join, the
  **joiner only** (§4, D8).

### 4. End of the source household after a join — option C, hybrid (D8)

Per merge mode chosen by the joiner (BR-HH-13, BR-HH-14):

1. **`NONE` (join without data).** S becomes `DISSOLVED`; the joiner has a read-only archive of S for **30 days** (view
   and export), then S is purged (BR-HH-14).
2. **`SHARED_DISCARD`.** The joiner's PERSONAL data moves to T. The SHARED expenses not brought, and their receipts, stay
   in S. S becomes `DISSOLVED` with a **joiner-only** archive for 30 days (consultation and export only), then it is
   purged.
3. **`SHARED_AS_SHARED` / `SHARED_AS_PERSONAL`.** Atomic transfer of the selected data, then **logical deletion** of S
   in the same transaction: `DISSOLVED`, `dissolved_at = purge_at =` approval instant, members' `left_at` set, **no**
   archive access. Physical purge is done by the purge job (HOUSEHOLD-008). The audit history of the moved entities is
   **not** purged together with S (see §8 and §9, D12).
4. **S budgets** are never transferred (BR-HH-13) and are purged with S in every mode.
5. **No physical deletion** before the end of the applicable window or retention obligation; nothing is lost silently.
6. **Information before confirmation.** Before confirming, the joiner is shown, per mode, what will be transferred, what
   will be kept read-only and until when, and what will be deleted (including S budgets).
7. The windows are configurable; the default is 30 days.

### 5. Join request lifecycle (D1, D2, D3, D13, D15, D17)

- **Consumption (D1).** The code is consumed (invitation `REDEEMED`) atomically with the creation of the `PENDING`
  request (conditional update + insert, one transaction). `UNIQUE (invitation_id)` (full, not partial) on
  `join_request`. After refusal, cancellation, expiry or invalidation a new code is required. Client retries use the
  existing `Idempotency-Key` mechanism.
- **States (D3, D13).** `PENDING → APPROVED | REJECTED | EXPIRED | CANCELLED | INVALIDATED`; terminal states are final.
  The requester may cancel a `PENDING` request; a cancelled request can never be approved. `INVALIDATED` carries a stable
  `closure_reason` (`TARGET_UNAVAILABLE`, `REQUESTER_INELIGIBLE`, `MERGE_NOT_POSSIBLE`) distinct from `EXPIRED`.
- **Validity (D2).** A request is valid 7 days from its creation (`couplefinance.household.join-request-validity`,
  default `P7D`); lazy check plus a ShedLock job. The invitation code itself stays valid 7 days from its creation
  (BR-HH-04).
- **Snapshot and re-validation (D13).** `source_household_id` is recorded at redemption. Approval re-validates under
  `FOR UPDATE` locks on the households (ascending id) and then on the request. Stable errors: 409
  `JOIN_REQUEST_NOT_PENDING`, `JOIN_REQUEST_EXPIRED`, `JOIN_TARGET_UNAVAILABLE`, `JOIN_REQUESTER_INELIGIBLE`,
  `JOIN_MERGE_NOT_POSSIBLE`; 403 `JOIN_APPROVAL_NOT_ALLOWED`.
- **Membership order in the approval transaction.** `uq_member_user_active (user_id) WHERE left_at IS NULL` is a
  partial unique index, which PostgreSQL never defers: the joiner's membership of S must be ended (`left_at`) **before**
  the membership of T is inserted, and the T membership must exist before any participant moves a row (composite member
  FKs). Order: lock households and request → re-validate → end the S membership → insert the T membership → merge
  participants → S status per §4 → request `APPROVED`.
- **Rights (D15).** Approve: the creator of the redeemed invitation, only while an active `MEMBER` of the `ACTIVE`
  target. Consult the inbox: any active member of the target. Refuse: any active member of the target (refusal only
  narrows access). The requester never decides.
- **Routes (D17).** Requester side under `/api/v1/me/join-requests` (`/preview`, collection `POST`, `/current`,
  `/{id}/cancel`), distinct from the inbox `/api/v1/households/me/join-requests`. Existing REST conventions,
  `Idempotency-Key` and non-enumeration protections apply.
- **Revocation (D5).** A `REDEEMED` invitation is not revocable (`INVITATION_NOT_REVOCABLE`); the inviter refuses the
  request instead.

### 6. Write guard

Every mutating use case takes `FOR KEY SHARE` on its household row first and re-reads the status; lifecycle transitions
(dissolution, merge, deletion) take `FOR UPDATE`. Lock order: households (ascending id), then module rows.

- **No lock upgrade.** The first lock a transaction takes on its household row is the strongest it will need. A use
  case that already serialises on the household row (e.g. invitation creation, which locks it `FOR UPDATE` to keep the
  member count stable) takes `FOR UPDATE` through the guard instead of `FOR KEY SHARE`: two transactions holding
  `FOR KEY SHARE` and both upgrading to `FOR UPDATE` would deadlock. A later `UPDATE` of the household row by the same
  transaction (`FOR NO KEY UPDATE`) does not conflict with other writers' `FOR KEY SHARE` and is allowed.
- **Fresh re-read.** Under READ COMMITTED, a locking `SELECT` re-reads only the locked row after waiting; the guard
  therefore reads the household status from the locked row and the caller's active membership in a **separate**
  statement afterwards.
- Only in a read-write transaction (PostgreSQL rejects row locks in read-only transactions); reads take no lock.
- Statements that touch no household data (e.g. claiming an `Idempotency-Key` in `infra`) may precede the guard.

### 7. Asynchronous analyses and categories during a merge (D9, D10, D11)

- **Analyses (D9).** The merge never waits on a job. Receipts are moved with their jobs; `RUNNING` / `PENDING` jobs are
  re-queued under T in the merge transaction. Every worker write is conditional on
  `(id, household_id, status = 'OPEN', analysis_status = 'RUNNING')`; a late result that no longer matches is discarded
  without counting as a failure; the next attempt re-checks the uploader's consent (BR-AI-05). After a full merge S keeps
  no receipt; otherwise S keeps exactly the receipts of the expenses left behind (asserted). For a join without data, S
  analyses are cancelled by a `HouseholdDissolved` reaction plus the write guard in the worker.
- **Categories (D10).** Mapped by the exact database normalisation of `uq_category_name` (`lower(name)`), whatever the
  archived state; never an implicit reactivation; unmatched categories are created in T with their archived state.
- **Merchant rules (D11).** T rules win on conflict. The joiner's personal rules move and stay personal. S household
  rules: `SHARED_AS_SHARED` → T household rules (T wins); `SHARED_AS_PERSONAL` → personal rules of the joiner (a same-key
  personal rule wins), so the partner never benefits from what became PERSONAL; `SHARED_DISCARD` → stay in S. Correction
  streaks follow the same scope mapping and are preserved unless a same-scope streak exists in T (T wins).

### 8. Audit of moved entities — principle and conditions (D12)

**Option A in principle:** the historical audit events stay in their household of origin, are never rewritten and never
systematically duplicated. **Mandatory conditions:**

1. no audit access by mere knowledge of an `entity_id`;
2. the history of an entity is read only after that entity is explicitly authorised in the caller's context;
3. audit events are immutable;
4. a merge event documents source household, target household and moved entities;
5. the purge policy accounts for the audit events of moved entities;
6. no global exception to cross-household controls;
7. future RLS policies stay consistent;
8. tests prove that an unauthorised user cannot read the history.

The realisation is option **A′** (§9), decided by the Tech Lead on 2026-10-13 (DOC-006, issue #118).

### 9. Realisation of D12 — option A′: audit kept in place, persistent provenance (DOC-006)

**Why not option A as first worded.** Reading a history "by `entity_id` across households" was a cross-household read
keyed only by an id (an exception to security.md §4.1 point 2) that the GA RLS policy `household_id = app.household_id`
cannot express; and the expense purge (BR-EXP-11, `JdbcExpensePurgeRepository`) deletes audit rows with
`household_id = <current household of the expense>`, so the rows left in S would never be purged with their expense
(BR-DAT "always purged together with its expense"). A′ removes both problems with an explicit provenance link.

**Scope.** Modules whose entities move by an `UPDATE` of `household_id` **and** that keep an audit trail: `expense`
(entity `EXPENSE`; items and refunds are audited on their expense / are expenses themselves) and `receipt` (entity
`RECEIPT`); `savings` in V1. Categories are mapped or created, not moved; budgets are not transferred; the household
audit stays with its household.

**Merge (inside the participant, same transaction).** For each moved entity: (1) the scoped `UPDATE` of
`household_id` S → T; (2) one row in `<module>.audit_provenance`; (3) one `MERGED` audit event written **in T**
(`household_id = T`, `entity_id`, owner per the entity's visibility in T, `actor_type = 'SYSTEM'`,
`actor_system = 'SYSTEM:household-merge'`, `changes` = origin household, mode, visibility before/after). Historical
events are never updated, never copied. The household module also writes one household audit event in S and one in T
(source, target, mode, number of moved entities per type), so condition 4 is met at both levels. Idempotent: a rerun
inserts nothing (`ON CONFLICT DO NOTHING` on the provenance key; the `MERGED` event is written only with a new
provenance row).

**Provenance model.** One table per participating module, in its own schema (ADR-001):
`expense.audit_provenance`, `receipt.audit_provenance` — see database-schema.md §11.1. Key facts:

- `(household_id, entity_id)` primary key: `household_id` is the household that received the entity (T); one
  provenance per entity.
- `origin_household_id` (S), `merged_at`, `merge_mode`; check `origin_household_id <> household_id`.
- **Depth 1 by construction.** A merge source is the sole member of an `ACTIVE` household; a merge target ends with
  two members and can only leave `ACTIVE` by dissolution (BR-HH-09). A household that received a merge can therefore
  never be the source of another one, and an entity moves at most once. The participant asserts it (no provenance row
  with `household_id = S`) and fails the merge otherwise; any future rule allowing chains requires a new ADR.
- `expense.audit_provenance (household_id, entity_id) → expense.expense (household_id, id)` (`NO ACTION`, not
  deferrable): a provenance row can never outlive its expense, and any hard-delete path that forgets the provenance
  (and therefore the origin audit rows) fails loudly instead of leaving orphans.
- `receipt.audit_provenance` has **no** FK to `receipt.receipt`: a receipt can be hard-deleted by its uploader while
  its audit is deliberately kept until retention (database-schema.md §11 "No FK to the entity"), so its provenance must
  live as long as that audit.
- No FK to `household.household` for either household id (cross-module, and S is purged while the link must stay),
  mirroring `audit_event`.
- Clients never send provenance: it is written only by the merge participant and read only server-side.

**Reading a history (conditions 1, 2, 6).** The client sends only the id of an entity of its current context. One
query per module, in its own schema:

```sql
-- expense; served by the provenance PK and ix_expense_audit_entity (household_id, entity_id, occurred_at DESC)
SELECT a.* FROM expense.audit_event a
JOIN expense.expense e
  ON e.id = :entityId AND e.household_id = :contextHousehold
 AND (e.owner_user_id IS NULL OR e.owner_user_id = :user)          -- 1. entity visible in the caller's context
WHERE a.entity_type = 'EXPENSE' AND a.entity_id = e.id
  AND (a.household_id = :contextHousehold
       OR a.household_id = (SELECT p.origin_household_id FROM expense.audit_provenance p
                            WHERE p.household_id = :contextHousehold AND p.entity_id = e.id))  -- 2. recorded origin
  AND (a.owner_user_id IS NULL OR a.owner_user_id = :user)          -- 3. row owner (BR-EXP-07, BR-EXP-10)
ORDER BY a.occurred_at DESC
```

The use case first loads the entity through its existing scoped read (404 otherwise); the query repeats the check so a
history can never be returned for an entity the caller cannot see. Every household id in the predicate is the
caller's own context or an origin recorded **by** that context: no unscoped method, no new ArchUnit exception, and no
other event of S is ever read (the predicate is per entity). Archive readers of T use the same query with the archive
context. Archive readers of S cannot reach moved entities (they no longer exist in S). Receipt audit rows are always
owned by the uploader, so they stay uploader-only.

**Purge (condition 5, BR-DAT).** Only in the dedicated purge classes (security.md §4.4):

| Event | Audit rows deleted | Provenance |
|---|---|---|
| Physical purge of S (HOUSEHOLD-008 / RECEIPT-008) | S rows **except** those of entities recorded as moved out of S (`NOT EXISTS` provenance with `origin_household_id = S`) | untouched (belongs to T) |
| Hard delete of a moved expense (BR-EXP-11 after 90 days; DATA-001 erasure) | its rows in T **and** in its recorded origin, in the same statement | deleted with them (required by the FK) |
| User deletion of a moved receipt | none (receipt audit is kept until retention, as today) | kept |
| Physical purge of T | T rows and the origin rows of every entity recorded by T | deleted |
| 24-month retention (`occurred_at`) | unchanged, any household | rows with `merged_at` older than the retention period, once no origin audit row of the entity remains (all of them are older than `merged_at`) |
| Account deletion (DATA-001) | erasure/redaction of the user's rows also covers the origin rows of their moved entities | as above |

Consequences: no orphan — an audit row whose household no longer exists is necessarily referenced by a provenance row,
which an integrity test asserts; no premature deletion — the S purge never touches moved entities' history; no
over-retention — every row still ends with its entity, its target household or the 24-month limit; foreign keys —
`audit_event` and provenance have no FK to `household.household`, so kept origin rows never block the deletion of the
S household row, and S is never referenced by a live row of another table.

**RLS (condition 7, GA).** `<module>.audit_provenance`: `household_id = app.household_id`. `expense.audit_event`:
`(household_id = app.household_id OR EXISTS (provenance row of app.household_id for the same entity whose origin is
household_id, and expense of app.household_id visible to app.user_id)) AND (owner_user_id IS NULL OR owner_user_id =
app.user_id)`; `receipt.audit_event` the same without the entity join (rows are uploader-owned). Purge classes keep the
`BYPASSRLS` role.

**Source of a moved expense.** A moved expense keeps its original `source` (`MANUAL`, `RECEIPT`); the merge never sets
`MERGE`. The transfer is traced by the `MERGED` event and the provenance row only. `MERGE` stays an unused value of
`ck_expense_source` (not in `ExpenseSource`); removing it would be a separate expand/contract change.

**Tests required (condition 8).** Another household, the partner on a PERSONAL (or `SHARED_AS_PERSONAL`) entity, a
former member of S without access, and a forged entity id each get 404 / no rows; originals byte-for-byte unchanged
after a merge; every purge row of the table above, including "S purged then expense purged in T leaves no row" and the
orphan integrity check; idempotent rerun of the merge.

**Option B (copy with provenance columns) — fallback, not chosen.** Simpler purge, but it duplicates history and
needs columns plus a unique index on every audited table; A′ keeps the history in one place with one small table per
module and a bounded (depth-1) lookup.

## Where recorded

| Decision | Document |
|---|---|
| Business rules (BR-HH-04, 09, 11, 12, 13, new 17 … 19) | business-rules.md §2 |
| Join request, participants, state diagram | domain-model.md §4 |
| `join_request` columns, purge through participants | database-schema.md §5.7, §12.4 |
| Merge through participants, end of S | database.md §4 |
| Recent authentication, archive routes | security.md §3, §4.3 |
| Routes, flow, MVP dissolution notification (D18) | architecture.md §6.4, §7 |
| User-facing journey and information before confirming | features.md F2, F15 |
| D12 realisation (A′): provenance, history read, purge, RLS; `source` of moved expenses | database-schema.md §7.1, §11, §12.4; security.md §4.1, §4.4; business-rules.md §11 (BR-DAT) |

## Consequences

- No module cycle; a new module (savings) joins the merge and purge by implementing two interfaces.
- The merge transaction spans several schemas on one connection with no external call; object storage is untouched
  (keys carry no household id).
- Intra-module composite FKs touched by the move (`expense_item`, refund self-reference, receipt children) may need to be
  deferrable. The intent is recorded; the exact constraints are verified on PostgreSQL, with full-rollback tests, by
  EXPENSE-010 / RECEIPT-006 before any `DEFERRABLE` change (Liquibase changesets in the owning modules).
- Sensitive operations may ask for a fresh login (UX cost accepted for rare actions); true step-up / MFA is V1.
- Archive routes add a second, explicit way to address household data; they are covered by the generated authorisation
  matrix.
- Every write takes one more lock; negligible at the expected volume.
- After a full merge the joiner cannot re-read S: by decision, everything user-relevant has moved to T.
- A solo household left by a join may linger as `DISSOLVED` for up to 30 days; the joiner is told before confirming.

## Alternatives rejected

- **Events for the merge**: not atomic; listeners would act after commit.
- **A new orchestration module above all modules**: an architecture change without benefit over two interfaces.
- **Password re-prompt endpoint**: a new credential flow, incompatible with future passkeys / Apple / Google sign-in.
- **`sid`-not-revoked only**: a long-lived stolen session could approve a merge.
- **Context-switch header for archives**: implicit, hard to cover in the OpenAPI authorisation matrix.
- **Application-only status check for writes**: not safe under concurrency (CLAUDE.md §9.1).
