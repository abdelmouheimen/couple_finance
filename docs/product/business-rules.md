# CoupleFinance — Business Rules

Version **0.3** — 2026-09-28 (v0.2: Staff Engineer review, see
[architecture.md §13](../architecture/architecture.md#13-review-findings-resolution); v0.3: domain model and schema
decisions, see [ADR-006](../architecture/adr/006-domain-model-and-persistence.md)).

Rules are identified by `BR-<AREA>-<NN>` so that code, tests and tickets can reference them. A rule marked
**[ASSUMPTION]** is a default chosen for the MVP that must still be confirmed.

Every rule must be enforced **on the server**. The mobile app may pre-validate for user experience, but the
server is the authority.

Related decisions: [ADR-002 household lifecycle](../architecture/adr/002-household-lifecycle-and-data-ownership.md) ·
[ADR-003 expense model](../architecture/adr/003-expense-model.md) ·
[ADR-004 AI output trust](../architecture/adr/004-ai-output-trust-model.md) ·
[ADR-005 AI consent & data protection](../architecture/adr/005-ai-consent-and-data-protection.md) ·
[ADR-006 domain model & persistence](../architecture/adr/006-domain-model-and-persistence.md)

---

## 1. Money (BR-MON)

| ID | Rule |
|---|---|
| BR-MON-01 | Money is always a pair *(amount, currency)*. An amount without a currency is invalid. Currency is an ISO 4217 code. |
| BR-MON-02 | Amounts are exact decimals. Floating-point types (`float`, `double`, JS `number` for arithmetic) are forbidden for money. Backend: `Money` value object backed by `BigDecimal`. Database: integer minor units (`BIGINT`). API: decimal **string** (`"12.50"`) plus currency code. |
| BR-MON-03 | The number of decimals follows the currency (EUR: 2, JPY: 0, …). Input with more decimals than the currency allows is rejected, not rounded. |
| BR-MON-04 | Operations between different currencies are forbidden without an explicit conversion. |
| BR-MON-05 | When a computation produces a non-representable **monetary** value (averages, divisions), rounding is `HALF_EVEN` to the currency's minor unit, applied once, at the end of the computation — unless a rule explicitly states otherwise (e.g. BR-SAV-04 rounds up). |
| BR-MON-06 | When an amount is divided into parts (expense items, even splits), parts are computed by integer division of minor units and the remainder is distributed one minor unit at a time, deterministically (first part first). The sum of parts always equals the original amount. |
| BR-MON-07 | Stored amounts are strictly positive; the economic sign is derived from the expense kind (BR-EXP-02). The maximum amount per expense is defined **per currency** in a reference table (order of magnitude EUR 1,000,000; e.g. JPY 150,000,000). It guards against typos and misreads. |
| BR-MON-08 | **No monetary value is ever computed, rounded, converted or aggregated by an LLM.** LLMs may *transcribe* amounts from a receipt image (subject to BR-RCP) and *refer to* computed facts through placeholders (BR-AI-10); all arithmetic happens in deterministic code. |
| BR-MON-09 | Percentages are computed from minor-unit integers with `BigDecimal`, rounded `HALF_EVEN` to one decimal for display. Breakdowns that must sum to 100 use BR-ANA-05. |

## 2. Household (BR-HH)

| ID | Rule |
|---|---|
| BR-HH-01 | A household has 1 or 2 active members. Membership is modelled as a list; the limit of 2 is a business rule, not a structural constraint. |
| BR-HH-02 | A user belongs to at most one **active** household at a time. |
| BR-HH-03 | Both members have identical rights on **shared** household data. Personal data belongs to its owner only (BR-EXP-07). |
| BR-HH-04 | Invitations: code of ≥ 128 bits of randomness (stored hashed), single use, valid 7 days, revocable by its creator, only valid while the household is `ACTIVE` and has 1 member. Redeeming a code creates a **pending join request** which the inviting member must **approve** in the app before the joiner gets any access. Redemption attempts are rate-limited per user and per IP. |
| BR-HH-05 | A household has one currency, one timezone (IANA id) and a period start day (1–28, default 1). A **budget period** is the range of expense dates `[start, next start)`. Expense dates are local calendar dates, so the timezone does **not** re-bucket expenses; it is only used to compute "today" (defaults, date validation, current period). |
| BR-HH-06 | Household currency cannot be changed once expenses exist. |
| BR-HH-07 | Changing the period start day takes effect at the next period boundary. The current period keeps its stored bounds; the following period starts at the old boundary and ends at the next occurrence of the new start day (if that transitional period would be shorter than 15 days, it is extended to the following occurrence). Every period is stored with explicit `start` and `end` dates; nothing is recomputed retroactively. |
| BR-HH-08 | Changing the household timezone is allowed at any time and affects only future "today" computations. |
| BR-HH-09 | Household status: `ACTIVE` → `DISSOLVED` → `DELETED`. In a two-member household, a member cannot "leave" individually: leaving **is** dissolution. Either member can dissolve the household unilaterally, without the partner's consent; the partner is notified. |
| BR-HH-10 | A dissolved household is **read-only** for both former members for 90 days (view and export only), then deleted. No one can ever join a dissolved household. |
| BR-HH-11 | After dissolution, each former member may create a new household, optionally seeded with a copy of **configuration** (categories, their own merchant rules, budget template) and **their own PERSONAL expenses**. SHARED expenses of the old household are never copied into a new household (they may be exported). |
| BR-HH-12 | A single-member household can be deleted by its member (soft delete, purged after 30 days, export offered). |
| BR-HH-13 | **Join with data (merge).** A user who is the sole member of household S may join household T bringing their data, if both use the same currency. The joiner chooses, before approval, what happens to S's SHARED expenses: bring them as SHARED (visible to the partner), convert them to PERSONAL, or not bring them (exported, then deleted with S). PERSONAL expenses stay PERSONAL. Categories are mapped by case-insensitive name, otherwise created in T. Household merchant rules of T win on conflict. S's budgets are discarded; S's savings goals move to T. The merge is atomic; S is deleted afterwards. |
| BR-HH-14 | A user who joins **without** data keeps their previous single-member household in `DISSOLVED` read-only state for 30 days (export offered), then it is deleted. |
| BR-HH-15 | Deleting an account dissolves the user's household first (BR-HH-09); data then follows BR-DAT. |

## 3. Expenses (BR-EXP)

| ID | Rule |
|---|---|
| BR-EXP-01 | An expense has: kind, amount (Money), date, expense items (category breakdown, BR-EXP-08), paid-by member, sharing type, created-by, and optional merchant, note, receipt, refunded expense. |
| BR-EXP-02 | Kind: `EXPENSE` (increases spending), `REFUND` (decreases spending), `TRANSFER` (money moved between the two partners — reimbursements; **excluded** from spending, budgets and analytics totals). Cash withdrawals are not recorded; purchases paid in cash are recorded as expenses. |
| BR-EXP-03 | A `REFUND` may link to the original expense of the same household. If linked: the sum of refunds linked to an expense cannot exceed its amount; the refund has the **same sharing type (and owner)** as the original; its date is on or after the original's date; its items default to the original's categories (proportionally, BR-MON-06). A refund counts in the period of **its own** date. An expense with live (non-deleted) refunds cannot be deleted. |
| BR-EXP-04 | A `TRANSFER` has a payer and a recipient, both members of the household; it is always SHARED; it requires a two-member household; it has **no expense items** (it is not spending) and **no receipt**. |
| BR-EXP-05 | The expense currency must equal the household currency (MVP). |
| BR-EXP-06 | The expense date is a calendar date. It cannot be more than 1 day after "today" (household timezone) nor older than 5 years. |
| BR-EXP-07 | Sharing type: `SHARED` — visible to both members, counts in household budgets and analytics. `PERSONAL` — owned by the member who paid it (paid-by = created-by); **visible only to its owner** (the partner cannot see its existence, amount, merchant, note, receipt or audit); excluded from household budgets, analytics and insights; included in the owner's personal views. Default: `SHARED`. |
| BR-EXP-08 | Expense items: an `EXPENSE` or `REFUND` has 1 to 10 items `(category, amount > 0, optional label)`, at most one item per category, whose sum equals the expense amount exactly (also enforced by the database at commit). A single-category expense has one item. Even splits use BR-MON-06. Expense items are financial categorisation, distinct from receipt line items (printed lines). |
| BR-EXP-09 | Edit rights: any member can edit or delete a SHARED expense; only the owner can edit or delete a PERSONAL expense. Only the payer can switch an expense between SHARED and PERSONAL. |
| BR-EXP-10 | Every change is recorded in an audit trail (who, when, old/new values). The audit of a PERSONAL expense is visible only to its owner. |
| BR-EXP-11 | Deletion is logical. Deleted expenses are excluded from all totals, budgets and analytics, can be **restored** within 90 days by anyone with edit rights, and are then purged with their items and audit rows. Deleting a SHARED expense created by the partner notifies the partner. |
| BR-EXP-12 | Concurrent modifications are detected by a version number (`If-Match`); a stale update is rejected with HTTP **412** and never silently overwrites; a missing `If-Match` on update is rejected with **428**. |
| BR-EXP-13 | Creation accepts an `Idempotency-Key` (scope: user, validity: 24 h). Same key + same request → original response. Same key + different request → 422. Same key while the first request is still in progress → 409 `REQUEST_IN_PROGRESS`. |
| BR-EXP-14 | Expense items must reference an **active** category of the household (or a system category). An unchanged item on a since-archived category remains valid on edit. |

## 4. Spending scope (BR-SCP)

| ID | Rule |
|---|---|
| BR-SCP-01 | **Household spending** for a date range = Σ items of non-deleted SHARED `EXPENSE` − Σ items of non-deleted SHARED `REFUND`. `TRANSFER` is excluded. This single definition is used by budgets, household analytics, insights and the assistant. |
| BR-SCP-02 | **Personal spending** of a user = same formula over that user's PERSONAL expenses. It is only ever shown to that user, labelled "personal", and never added into a household figure. |
| BR-SCP-03 | Every total in the UI and API is labelled with its scope (`HOUSEHOLD` or `PERSONAL`). |

## 5. Receipts & AI extraction (BR-RCP)

| ID | Rule |
|---|---|
| BR-RCP-01 | A receipt consists of 1–5 images (JPEG, PNG, HEIC — for long receipts photographed in parts) **or** one PDF of ≤ 5 pages. Each file ≤ 10 MB, total ≤ 25 MB. Content type is verified from file bytes. |
| BR-RCP-02 | AI extraction produces a **draft**, never an expense. An expense is created only when a member explicitly confirms the draft. |
| BR-RCP-03 | Extracted output must conform to the extraction schema (ai.md §4.2). Non-conforming output is a failed extraction. |
| BR-RCP-04 | Amounts and dates are transcribed by the model **as printed** and parsed by deterministic code. Line items are **signed** and typed: `ITEM`, `DISCOUNT`, `DEPOSIT`, `DEPOSIT_RETURN`, `TIP`, `FEE`, `OTHER`. A negative total proposes kind `REFUND`. Unparsable or out-of-range values (BR-MON-07) are `INVALID`. |
| BR-RCP-05 | **Ambiguity is never resolved silently.** If a numeric string admits more than one valid reading (e.g. `1.234`, `1,234`) or a date more than one valid date (e.g. `03/04/2026`), or a currency symbol more than one currency (`$`, `kr`), the parser may resolve it only if all available hints agree (formats reported by the model, receipt language/country, other amounts on the same receipt, household locale). Otherwise the field is `AMBIGUOUS` and the user chooses among the candidate readings. |
| BR-RCP-06 | Tax mode is `INCLUSIVE` (prices include tax; tax lines are informational), `EXCLUSIVE` (subtotal + tax = total) or `UNKNOWN` (no tax check). The subtotal/tax check applies only to `EXCLUSIVE`. |
| BR-RCP-07 | Consistency checks (with `BigDecimal`, tolerance = max(0.02 currency units, 1% of total)): signed line-item sum vs total; subtotal/tax vs total (BR-RCP-06); payment lines (tendered − change) vs total. A failed check marks the total `INCONSISTENT`. A total with **no** corroborating signal (no lines, no payment line, no tax breakdown) is `NOT_CORROBORATED`. The server never "fixes" a value. |
| BR-RCP-08 | Date: valid, ≤ 1 day after today, ≤ 5 years old (same as BR-EXP-06); older than 1 year → warning `STALE`. |
| BR-RCP-09 | If the detected currency differs from the household currency, the draft is flagged; the user enters the amount in household currency (MVP). |
| BR-RCP-10 | **Mandatory review:** regardless of field statuses, the user must explicitly acknowledge the **total** and the **date** (the confirm action displays them). Every field whose status is not `OK` must be individually resolved before confirmation. |
| BR-RCP-11 | Model-reported confidence is used only after calibration against the golden dataset (ai.md §9); thresholds are per field. Validation statuses always take precedence over confidence. |
| BR-RCP-12 | The model classifies the document type: `RECEIPT`, `CARD_SLIP`, `INVOICE`, `QUOTE`, `REFUND_RECEIPT`, `OTHER`. `QUOTE`/`OTHER` → warning "not a proof of payment". `REFUND_RECEIPT` → kind `REFUND` proposed. |
| BR-RCP-13 | Tips: a `TIP` line or a handwritten tip/total is extracted as such; the user confirms the final amount paid. Multiple payment methods do not matter: one expense for the total. |
| BR-RCP-14 | Duplicates (warning, never a block): (a) byte-identical upload (SHA-256 of original bytes); (b) perceptual-hash distance below threshold within the household; (c) existing expense with same total, date ±1 day and merchant key equal or trigram similarity ≥ 0.6. The user can instead **attach** the receipt to the existing expense (e.g. card slip + itemised bill). |
| BR-RCP-15 | A receipt is confirmed **exactly once**. Concurrent confirmations: one succeeds, the others get 409 `RECEIPT_ALREADY_CONFIRMED`. A receipt is linked to at most one expense; an expense to at most one receipt (multi-category receipts use expense items). |
| BR-RCP-16 | A draft is visible only to its uploader until confirmed; afterwards the receipt follows the visibility of its expense (BR-EXP-07). |
| BR-RCP-17 | A `FAILED` analysis can be re-run manually (max 3 times per receipt). Receipts uploaded while the uploader had not consented to AI processing are never processed retroactively; the user may request analysis explicitly after consenting. |
| BR-RCP-18 | Values confirmed by the user are stored separately from raw AI output. Raw AI output is kept 90 days (audit and quality measurement). |
| BR-RCP-19 | A draft not confirmed within 30 days is abandoned; its images are deleted 30 days later. |
| BR-RCP-20 | A receipt image is retained while its expense exists, unless the user removes it. Images older than 2 years move to cold storage **[proposal]**. |

## 6. Categories (BR-CAT)

| ID | Rule |
|---|---|
| BR-CAT-01 | The system provides a default set (Groceries, Housing, Utilities, Transport, Restaurants, Health, Leisure, Shopping, Travel, Children, Gifts, Other). Households can add custom categories. Flat list (MVP). |
| BR-CAT-02 | Category names are unique per household (case-insensitive). |
| BR-CAT-03 | System categories are identified by a code, displayed in each user's language, and cannot be renamed, archived or hidden by a household. Categories are **never hard-deleted**; "delete" means archive. Archived categories are hidden from pickers, stay on historical expenses and analytics, and are not copied into new budget periods. |
| BR-CAT-04 | Suggestion order: (1) user merchant rule (for PERSONAL) / household merchant rule (for SHARED), (2) AI suggestion, (3) "Other". The source of the suggestion is shown to the user. It stays a suggestion until the expense is saved. |
| BR-CAT-05 | A merchant rule is created or updated when the user explicitly chooses "always use this category for this merchant", or after **two consecutive** saves of the same merchant with the same category differing from the suggestion. Rules learned from SHARED expenses are household-level; rules learned from PERSONAL expenses are user-level and invisible to the partner. |
| BR-CAT-06 | An AI suggestion must be one of the active categories offered; any other value is discarded. |
| BR-CAT-07 | Merchant normalisation is a deterministic, versioned function (case folding, accent/punctuation removal, legal suffixes, store numbers, city names). |

## 7. Budgets (BR-BUD)

| ID | Rule |
|---|---|
| BR-BUD-01 | A budget belongs to a household and a budget period (explicit start/end, BR-HH-07). At most one overall budget and at most one limit per category per period. |
| BR-BUD-02 | A budget has at least one limit (overall or category). Budgets do not recur automatically; the user can copy the previous period's budget. No rollover (MVP). Personal budgets are out of scope (MVP). |
| BR-BUD-03 | Consumption = household spending (BR-SCP-01) within the period; for a category budget, only expense items of that category. |
| BR-BUD-04 | Category limits need not add up to the overall budget; if their sum exceeds it, the app warns but does not block. |
| BR-BUD-05 | Remaining = limit − consumed (may be negative). Consumption % per BR-MON-09. |
| BR-BUD-06 | Status: `ON_TRACK` < 80%, `WARNING` 80–100%, `EXCEEDED` > 100%. The first time a budget reaches a status in a period, **exactly one** notification is sent (uniqueness enforced in the database). Dropping back below does not re-arm it in the same period. |
| BR-BUD-07 | Budgets of past periods can be edited; edits are audited. |

## 8. Savings goals (BR-SAV) — V1

| ID | Rule |
|---|---|
| BR-SAV-01 | A goal belongs to the household (shared). It has a name, a target amount (> 0), an optional target date (in the future at creation) and a lifecycle (`ACTIVE`, `ARCHIVED`). Its progress state (`ON_TRACK`, `REACHED`, `OVERDUE`) is **derived** from saved amount, target, target date and today — never stored. |
| BR-SAV-02 | Saved = Σ contributions − Σ withdrawals, each attributed to a member; any active member may record a movement attributed to either member. The saved balance cannot go below zero (checked under a lock on the goal, backed by a database check). Movements are immutable; a mistaken movement is deleted (audited) if the balance stays ≥ 0. |
| BR-SAV-03 | Contributions are set-asides of money, not expenses: they do not appear in expense history nor consume budgets. CoupleFinance holds no money. |
| BR-SAV-04 | Required per period = (target − saved) / remaining periods, where remaining periods counts the current period and is ≥ 1; rounded **up** to the minor unit. If the target date has passed and the goal is not reached, its progress state is `OVERDUE` and the required amount equals the remaining amount. Undefined without target date. |
| BR-SAV-05 | A goal is `REACHED` whenever saved ≥ target; it can still receive contributions. Crossing the target emits one notification. |

## 9. Analytics (BR-ANA)

| ID | Rule |
|---|---|
| BR-ANA-01 | Household analytics use household spending (BR-SCP-01) only. Personal analytics use personal spending (BR-SCP-02) and are visible only to their owner. Computed by the backend; the mobile app never aggregates money. |
| BR-ANA-02 | Totals are computed in minor units with integer arithmetic; derived values follow BR-MON-05 / BR-MON-09. |
| BR-ANA-03 | "3-period average" = average of the 3 complete periods preceding the reference period, restricted to periods on or after the household's **tracking start** (= earliest of household creation date and earliest expense date). Minimum 1 period; otherwise not shown. |
| BR-ANA-04 | Per-member figures use *paid by*, never *created by*. |
| BR-ANA-05 | Percent of total per category uses the largest-remainder method so displayed percentages sum to 100. |
| BR-ANA-06 | Net category spending in a period can be negative (refunds > purchases). It is displayed as-is and excluded from the percentage breakdown (shown separately). |

## 10. AI usage (BR-AI)

| ID | Rule |
|---|---|
| BR-AI-01 | AI is used only for: receipt transcription, category suggestion, wording of insights, and (V2) conversational question answering through read-only tools. |
| BR-AI-02 | Every number presented to the user originates from deterministic code, never from free LLM text. |
| BR-AI-03 | AI output is untrusted input: schema-validated, business-validated, never executed nor used to build queries. |
| BR-AI-04 | Business logic depends only on AI ports; the provider can change without changing business modules. |
| BR-AI-05 | **Consent is per user, explicit, opt-in**, separate for (a) receipt analysis and (b) insights & assistant, and withdrawable at any time. A receipt is sent to an AI provider only if its **uploader** consented to (a). AI-worded household insights require **both** members' consent to (b); otherwise template-only insights are used. |
| BR-AI-06 | Data minimisation: categorisation sends the normalised merchant name only (no line items, no amounts). No names, emails or account identifiers are ever sent. Receipt images pass through the redaction step decided by the DPIA (ADR-005). |
| BR-AI-07 | Every AI call is logged (provider, model, prompt version, latency, tokens, cost, outcome) without content. |
| BR-AI-08 | A DPIA (data protection impact assessment) covering receipt processing — including health-related purchases — is completed before launch. |
| BR-AI-09 | Production receipts may enter the evaluation dataset only with a **separate** opt-in, after human-reviewed redaction, in separate storage with its own retention. |
| BR-AI-10 | Insight text uses placeholders for all values; every directional or causal claim (more/less, above/below, "mainly due to") must match a deterministic fact field and is verified before display. |

## 11. Data lifecycle & erasure (BR-DAT)

| Data | On account deletion | On household dissolution |
|---|---|---|
| Profile, credentials, tokens, device tokens | Deleted immediately | Unchanged |
| User's PERSONAL expenses, their receipts and audit | Purged within 30 days | Stay with owner; can be copied to their new household (BR-HH-11) |
| SHARED expenses | Remain in the dissolved household's read-only archive for the partner; payer/creator shown as "Former member"; **notes written by the deleted user are redacted** | Read-only archive 90 days, then deleted |
| Audit rows | Actor → "Former member"; free-text values redacted | Deleted with the archive |
| User-level merchant rules, consents | Deleted | Stay with the user |
| Raw AI output | 90 days max (BR-RCP-18) | same |
| `ai_call` log (no content) | Kept 13 months, pseudonymous | same |
| Audit trail general retention | 24 months, and always purged together with its expense | — |
| Backups | Expire after 30 days (erasure effective when backups expire; documented) | same |

## 12. Notifications (BR-NOT) — V1

| ID | Rule |
|---|---|
| BR-NOT-01 | Notifications do not show amounts on the lock screen by default; a user may opt in to amounts. |
| BR-NOT-02 | Nothing about a PERSONAL expense ever notifies or is visible to the partner. |
| BR-NOT-03 | The partner is notified of: join request approved, household dissolved, their SHARED expense deleted by the other member, budget status changes (BR-BUD-06), receipt ready for review (uploader only). |
| BR-NOT-04 | Each notification type can be disabled per user, except household dissolution. |
