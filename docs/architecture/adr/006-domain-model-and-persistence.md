# ADR-006 — Domain model and persistence model

- **Status:** Accepted
- **Date:** 2026-09-28
- **Amends:** ADR-003 (terminology), business rules v0.2 → v0.3, database.md v0.2 → v0.3
- **Details:** [domain-model.md](../domain-model.md), [database-schema.md](../database-schema.md)

## Context

Before writing migrations, the aggregates, their invariants and their physical representation had to be fixed. The
design work surfaced a few gaps in the v0.2 documents: "allocation" was ambiguous next to receipt line items;
transfers were required to have categories; a refund could leak a personal expense; the expense–receipt link was
stored on both sides; several invariants relied only on application code or locks; and a single receipt status mixed
the evidence lifecycle with AI processing.

## Decision

### Domain model

1. **Receipt is evidence, Expense is financial data.** Receipt amounts are transcriptions (`ExtractedAmount`: raw,
   candidates, status) and are never counted. Only a member creates an expense, through the expense API. The single
   link is `Expense.receiptId`, unique; editing the expense never changes the receipt (P4).
2. **ExpenseItem** replaces *allocation* (P1): 1–10 items per `EXPENSE`/`REFUND`, one per category, summing exactly to
   the amount; `TRANSFER` has none (P2). Receipt line items remain printed lines, distinct from expense items.
3. **Refunds** share the original's visibility and are dated on/after it; an expense with live refunds cannot be
   deleted (P3).
4. **Receipt state** is two dimensions: `status` (OPEN, CONFIRMED, ATTACHED, ABANDONED) and `analysis_status`
   (SKIPPED_NO_CONSENT, PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED). Confirmation is allowed from any OPEN receipt;
   late analysis results are discarded by conditional update (P5, S8).
5. **ReceiptConfirmation**: immutable snapshot of what was confirmed, kept with the evidence (S10).
6. **Savings goals** store only `ACTIVE`/`ARCHIVED`; `ON_TRACK`/`REACHED`/`OVERDUE` are derived; a running
   `saved` balance is kept (P9).
7. **System categories** are identified by a code and displayed in each user's language (S7).

### Persistence

8. Money as `bigint` minor units + `char(3)`; per-currency limits in reference data (S1).
9. **Database-enforced isolation and integrity**: composite FKs on `(household_id, …)` (S2); `(household_id, currency)`
   → household pins currency and freezes it once used (P7, S3); user columns → `household_member` (P8, S4); member
   `seat` with partial unique index caps active members at two (P6, S5).
10. **Σ items = amount** enforced by a deferred constraint trigger at commit (P10, S12).
11. Explicit `budget_period` rows with a no-overlap exclusion constraint; budgets reference real periods (S6).
12. Duplicate-receipt signals are non-unique indexes — warnings, never blocks (S11).
13. Per-module append-only `audit_event` tables written by the application (S13); consent as an event log (S14);
    `infra` schema for idempotency (S15); `ON DELETE CASCADE` only inside aggregates, module-by-module purge (S16).

## Consequences

- Positive: the most important invariants (tenancy, currency, member count, exact item sums, single confirmation,
  non-negative savings) hold even against application bugs or manual SQL; evidence and money cannot be confused in
  code or queries; AI processing can fail, lag or be skipped without blocking the user.
- Negative: more composite keys and supporting unique indexes; one deferred trigger to maintain; household purge must
  be orchestrated across modules; cross-module references (categories, receipt link) still need application checks
  and a nightly consistency job.

## Alternatives rejected

- **Single receipt status enum**: cannot express "confirmed while analysis still running" without special cases.
- **`numeric` money columns**: allow sub-minor-unit values; no benefit over integers here.
- **Application-only invariants**: insufficient for money and tenancy guarantees (CLAUDE.md §4).
- **Global audit table**: breaks module data ownership (ADR-001).
