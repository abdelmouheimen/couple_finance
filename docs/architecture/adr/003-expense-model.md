# ADR-003 — Expense model: kinds, allocations and spending scope

- **Status:** Accepted (terminology amended by ADR-006: *allocation* → *expense item*)
- **Date:** 2026-09-28

## Context

In v0.1 an expense was a positive amount with one category, shared or personal. The review found that:

- refunds, partial refunds and reimbursements between partners could not be represented, leading to deleted
  history or double counting;
- one supermarket receipt often covers several categories, so category budgets would be wrong;
- budgets counted only shared expenses while analytics counted everything — two different "spent" figures.

This model is the core of the database and must be settled before implementation.

## Decision

1. **Kinds**: `EXPENSE` (+), `REFUND` (−, optionally linked to the original, bounded by it), `TRANSFER`
   (partner → partner, excluded from spending). Stored amounts are always positive; the sign comes from the kind
   (BR-EXP-02 … BR-EXP-04, BR-MON-07).
2. **Allocations**: an expense has 1–10 category allocations whose amounts sum **exactly** to the expense amount
   (BR-EXP-08, BR-MON-06). Category budgets and analytics use allocations.
3. **One spending definition** (BR-SCP-01): household spending = SHARED expenses − SHARED refunds, transfers
   excluded. Budgets, household analytics, insights and the assistant all use it. Personal spending is the same
   formula over the user's PERSONAL expenses, shown only to them. Every aggregate carries its scope.
4. The spending SQL lives in the **expense module's query API**; other modules call it.

## Consequences

- Positive: refunds and reimbursements are recorded truthfully; category budgets are accurate for mixed receipts;
  no contradictory totals; a future settle-up feature can build on `TRANSFER` and `paid_by`.
- Negative: more complex entry UI (allocation editor, kind selector — hidden behind "more options"); net category
  spending can be negative in a period (BR-ANA-06); one more table and an invariant to enforce.
- Custom split ratios / who-owes-whom remain open; they will add an `expense_share` table without changing this
  model.

## Alternatives rejected

- **Negative amounts instead of kinds**: ambiguous semantics (refund vs correction vs transfer), error-prone sums.
- **Split receipts into several expenses**: loses the one-receipt-one-purchase link and complicates duplicates.
- **Configurable budget scope**: two definitions of "spent" would reappear; kept as a single rule.
