# CoupleFinance — Features

Version **0.2** — 2026-09-28. Each feature lists its phase, user stories and acceptance criteria. Business
rules referenced as `BR-xxx` are defined in [business-rules.md](business-rules.md).

Phases: **MVP** → **V1** → **V2** (see [vision.md](vision.md#7-scope-by-phase)).

---

## F1. Accounts & authentication — MVP

**Stories**
- As a user, I can sign up with email and password and verify my email.
- As a user, I can log in, stay logged in on my phone, and log out (including from all devices).
- As a user, I can reset a forgotten password.
- As a user, I can delete my account.

**Acceptance criteria**
- Sessions survive app restarts; the refresh token is stored in OS secure storage (Keychain / Keystore).
- Parallel requests never cause a spurious logout (single-flight token refresh, see security.md §3).
- Optional biometric unlock of the app (local only).
- Account deletion dissolves the household first (BR-HH-15) and follows BR-DAT; the user is shown exactly
  what the partner will keep.

## F2. Household lifecycle — MVP

**Stories**
- As a user, I can create a household (name, currency, timezone, period start day).
- As a member, I can invite my partner with a single-use code/link, and I **approve** the join request
  before my partner sees anything.
- As an invited user who already tracks expenses alone, I can **join with my data** or join without it.
- As a member, I can dissolve the household (e.g. after a separation) without my partner's consent.
- As a former member, I can browse and export the dissolved household for 90 days, and start a new
  household seeded with my configuration and my personal expenses.

**Acceptance criteria**
- At most two active members (BR-HH-01); one active household per user (BR-HH-02).
- Creation per BR-HH-16 (`POST /api/v1/households`, Issue #1): only the name is required; currency, timezone and
  period start day default to EUR, Europe/Paris and 1.
- Invitation rules BR-HH-04 (≥ 128-bit code, 7 days, approval step, rate limits).
- Merge rules BR-HH-13; the joiner explicitly chooses what happens to their SHARED expenses and is told
  the partner will see anything brought as SHARED.
- Dissolution rules BR-HH-09 … BR-HH-11; a new partner can never see a former partner's data.
- Onboarding asks early whether the user will share with a partner, to encourage one household from the start.

## F3. Expense entry — MVP

**Stories**
- As a member, I can record an expense: amount, date, category, merchant (optional), note (optional),
  paid by, shared or personal.
- As a member, I can split one expense into several expense items, one per category (e.g. a supermarket receipt).
- As a member, I can record a **refund** (optionally linked to the original expense) and a **transfer**
  between my partner and me (reimbursement).
- As a member, I can edit, delete and restore (within 90 days) SHARED expenses and my own PERSONAL ones.
- As a member, I see who created and who last modified a SHARED expense.

**Acceptance criteria**
- Minimal form: amount, date (defaults to today in household timezone), category (pre-suggested with source).
- Kinds and signs per BR-EXP-02 … BR-EXP-04; expense items per BR-EXP-08 (transfers have none).
- PERSONAL expenses are invisible to the partner (BR-EXP-07); the UI makes the sharing choice explicit.
- Stale updates rejected with 412 (BR-EXP-12); retried submissions don't duplicate (BR-EXP-13).
- Deleting a partner's SHARED expense notifies the partner (BR-EXP-11).

## F4. Receipt capture — MVP

**Stories**
- As a member, I can photograph a receipt (up to 5 photos for a long receipt) or pick an image/PDF.
- As a member, I see the receipt status (uploading, analysing, ready for review, failed) and can retry a
  failed analysis.
- As a member, I can attach a receipt to an existing expense instead of creating a new one.
- As a member, I can view the receipt attached to an expense.

**Acceptance criteria**
- Formats and limits per BR-RCP-01; images compressed client-side; EXIF stripped server-side.
- Upload survives poor connectivity; the user can leave the screen.
- A draft is visible only to its uploader until confirmed (BR-RCP-16).
- Without AI consent, the receipt is stored for manual entry only (BR-AI-05, BR-RCP-17).

## F5. AI receipt analysis & review — MVP

**Stories**
- As a member, after uploading a receipt, I get a draft (merchant, date, total, tax mode, signed line items,
  document type, suggested categories).
- As a member, I resolve every flagged field, explicitly acknowledge the total and the date, and confirm.
- As a member, when a value is ambiguous (e.g. `03/04` or `1.234`), I choose between the possible readings.

**Acceptance criteria**
- Validation per BR-RCP-03 … BR-RCP-14; statuses `OK`, `LOW_CONFIDENCE`, `AMBIGUOUS`, `INCONSISTENT`,
  `NOT_CORROBORATED`, `MISSING`, `INVALID`, `STALE`.
- Mandatory review of total and date (BR-RCP-10); the confirm button states the amount and date.
- Receipt line items are grouped into proposed expense items (one per category) in one step (BR-EXP-08); after
  confirmation the expense and the receipt evolve independently.
- Duplicate warnings with "attach to existing expense" (BR-RCP-14).
- Exactly-once confirmation (BR-RCP-15).
- If extraction fails, the user fills the draft manually with the images visible.

## F6. Automatic categorisation — MVP

**Stories**
- As a member, a category is suggested when I enter a merchant or review a receipt, with its source
  (your rule / household rule / AI / default).
- As a member, I can choose "always use this category for this merchant".
- As a member, I can manage household categories (create, rename, archive, icon/colour).

**Acceptance criteria**
- Suggestion order and learning per BR-CAT-04, BR-CAT-05; rules learned from personal expenses stay private.
- Rule-based suggestion < 1 s p95; AI suggestion asynchronous, never blocks saving.
- Categories are archived, never deleted (BR-CAT-03).

## F7. Monthly budgets — MVP

**Stories**
- As a member, I can set an overall budget for a budget period and copy last period's budget.
- As a member, I see consumed and remaining amounts, updated immediately.

**Acceptance criteria**
- Consumption = household spending (BR-SCP-01, BR-BUD-03); the screen labels the scope.
- Status thresholds and exactly-once notifications (BR-BUD-06).

## F8. Budgets by category — MVP

**Stories**
- As a member, I can set a limit per category and see per-category progress.

**Acceptance criteria**
- Category consumption is based on expense items; warning if limits exceed the overall budget (BR-BUD-04).

## F9. Expense history — MVP

**Stories**
- As a member, I browse household expenses and my personal expenses, newest first.
- As a member, I filter by period, category, kind, paid by, shared/personal, has receipt, and search by
  merchant or note.

**Acceptance criteria**
- Cursor-based pagination; smooth on 5,000+ expenses.
- Filter totals computed server-side and labelled with their scope (BR-SCP-03).
- The partner's PERSONAL expenses never appear.

## F10. Monthly analytics — MVP

**Stories**
- As a member, for a period I see household spending total, per category, per member, comparison with the
  previous period and the 3-period average, budget consumption; and separately my personal spending.

**Acceptance criteria**
- Rules BR-ANA-01 … BR-ANA-06; refunds net, transfers excluded; negative categories shown separately.

## F11. Charts — MVP

**Stories**
- Category breakdown, daily cumulative household spending vs budget line, 6-period trend, per-member split.

**Acceptance criteria**
- Charts render analytics API data; no aggregation on device.
- Accessible colours, labels readable without colour, amounts formatted per user locale.

## F12. Savings goals — V1

**Stories**
- As a member, I create a shared goal, record contributions and withdrawals, and see progress and the amount
  needed per period.

**Acceptance criteria**
- Rules BR-SAV-01 … BR-SAV-05, including the derived `OVERDUE` state.

## F13. AI financial insights — V1

**Stories**
- As a member, I receive a short period summary and occasional insights (category spike, budget at risk,
  unusual expense, goal off track), in my own language.
- As a member, I can see the underlying figures, dismiss an insight or mark it not useful.

**Acceptance criteria**
- Generated from deterministic facts on household scope only (never from PERSONAL data).
- All values via placeholders; direction and causal claims verified against facts (BR-AI-10).
- AI wording only if both members consented; otherwise template text (BR-AI-05).
- Rendered per user locale (partners may use different languages).
- Neutral tone, no blame of either partner.

## F14. Notifications — V1

- Push notifications per BR-NOT-01 … BR-NOT-04; no amounts on the lock screen by default.

## F15. Data export & privacy controls — V1 (export of dissolved household: MVP)

- CSV export of household (SHARED) expenses and of one's own personal expenses; personal data export.
- Per-user AI consents: receipt analysis; insights & assistant (BR-AI-05).
- Separate opt-in to contribute receipts to the quality dataset (BR-AI-09).

## F16. Conversational financial assistant — V2

**Stories**
- As a member, I ask questions in natural language: "How much did we spend on restaurants in March?".

**Acceptance criteria**
- Read-only tools; every number from tool results; household scope plus the asking user's own personal data
  only; never the partner's personal data.
- Requires the asking user's consent (b); conversation retention limited and deletable.

---

## Feature → module map

| Feature | Backend module(s) |
|---|---|
| F1 | `identity` |
| F2 | `household` (merge orchestrates `expense`, `categorization`, `savings`, `receipt`) |
| F3, F9 | `expense` |
| F4, F5 | `receipt`, `ai` |
| F6 | `categorization`, `ai` |
| F7, F8 | `budget` |
| F10, F11 | `analytics` |
| F12 | `savings` |
| F13 | `insight`, `analytics`, `ai` |
| F14 | `notification` |
| F15 | `identity` (consents, personal data export), `expense` |
| F16 | `assistant`, `analytics`, `ai` |
