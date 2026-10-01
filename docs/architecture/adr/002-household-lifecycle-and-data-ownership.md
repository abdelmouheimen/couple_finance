# ADR-002 — Household lifecycle and data ownership

- **Status:** Accepted
- **Date:** 2026-09-28
- **Supersedes:** BR-HH-07 and BR-EXP-05 of business rules v0.1

## Context

Business rules v0.1 let a member leave a household that then continued with the remaining member, who could
invite someone new. Since all expenses (including personal ones) were visible to all members, a **new partner
would inherit the entire history of the former partner** — personal expenses, notes, receipt images, audit.
There was also no way to remove a partner after a separation, and no way to bring existing data when two people
who had each started alone decided to share a household — the most common onboarding path.

Real relationships start separately, merge, and sometimes end, occasionally in conflict. Financial monitoring of a
partner is a recognised form of coercive control. The data model must be safe by construction.

## Decision

1. **Joining requires approval.** An invitation code (≥ 128-bit, single use, 7 days) only creates a join request;
   the inviting member approves it before any access is granted (BR-HH-04).
2. **Join with data.** A sole member of household S can merge S into T (same currency). The joiner decides whether
   S's SHARED expenses become SHARED in T, become PERSONAL, or are left behind (BR-HH-13).
3. **Leaving = dissolution.** In a two-member household there is no individual "leave": either member can dissolve
   the household unilaterally. The household becomes `DISSOLVED` and read-only; both former members can view and
   export it for 90 days; then it is deleted (BR-HH-09, BR-HH-10).
4. **No inherited history.** Nobody can join a dissolved household. A former member can start a new household
   seeded with configuration and **their own PERSONAL expenses** only — never shared history (BR-HH-11).
5. **Personal means private.** PERSONAL expenses (and their receipts, audit and learned rules) are visible only to
   their owner and excluded from every household aggregate (BR-EXP-07, BR-SCP).
6. **Account deletion** dissolves the household first; data then follows the field-level BR-DAT policy
   (tombstoned user, redacted notes and audit).

## Consequences

- Positive: a future partner can never see a former partner's data; either partner can end the sharing at any time;
  onboarding without data loss; privacy model that is simple to explain and test.
- Negative: a person who continues after a separation starts a new household and loses the *live* shared history
  (kept only as 90-day archive + export). Accepted: shared history belongs to both.
- Merge requires cross-module orchestration in one transaction (database.md §4); every table must carry
  `household_id` (already required).
- Personal privacy adds an `owner_user_id` predicate to queries and a dedicated privacy test matrix.

## Alternatives rejected

- **Keep household, restrict new member to data after join date**: history remains mixed, complex filtering
  everywhere, the remaining member still keeps the ex's personal data.
- **Consent-based removal of a partner**: unsafe in conflictual separations.
- **Personal expenses visible to partner**: incompatible with the "no surveillance" principle.
