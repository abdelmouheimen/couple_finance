# CoupleFinance — Product Vision

## 1. Problem

Couples who share a household spend money from different accounts, cards and cash, at different times, and
usually without a shared, trusted picture of where the money goes. The common workarounds — a shared
spreadsheet, a notes app, a banking app per person — fail for the same reasons:

- **Capture is tedious.** Typing each purchase is friction; receipts get lost.
- **No shared truth.** Each partner sees only their own accounts. Conversations about money start from
  different numbers.
- **Budgets are abstract.** A monthly limit is set once and forgotten; overspending is discovered at the end
  of the month, when it is too late to adjust.
- **Goals are disconnected from spending.** "Save for a holiday" is not linked to "we spent 30% more on
  restaurants this month".

## 2. Vision

> CoupleFinance is the household's single, trusted ledger: two partners capture spending in seconds, see the
> same numbers, stay within the budgets they agreed on together, and progress towards shared goals — with AI
> doing the tedious work and never the arithmetic.

## 3. Target users

| Persona | Description | Primary need |
|---|---|---|
| **The organiser** | The partner who usually tracks money. Comfortable with budgets. | Less manual work; a partner who actually participates. |
| **The participant** | The partner who spends but rarely tracks. Low tolerance for friction. | Capturing an expense in < 10 seconds, or just snapping a receipt. |
| **The couple** (as a unit) | Two adults sharing a home, rent, groceries, bills, possibly children. | A shared, non-judgemental view to discuss money with. |

Out of scope as target users: businesses, flatmates groups of 3+, freelancers doing bookkeeping, investment
tracking.

## 4. Product principles

1. **One household, one truth — and a private space.** Both partners see the same *shared* data, totals and
   budgets. Each partner may also keep *personal* expenses that the other never sees. Numbers are computed
   once, on the server, deterministically, and always labelled with their scope (household or personal).
2. **Capture in seconds.** Photo of a receipt, or a three-field manual form. Everything else is optional.
3. **AI assists, humans confirm.** AI proposes (receipt fields, category, insight wording); a user confirms.
   No AI output becomes financial data without validation and, for receipts, explicit user confirmation.
4. **The machine never guesses money.** All amounts, totals, percentages, budget consumption and projections
   are computed by deterministic code with exact decimal arithmetic. LLMs never compute or invent a number.
5. **Privacy by default.** Financial data and receipt images are sensitive. Minimal data leaves our system;
   what is sent to an AI provider is documented and consented to.
6. **Non-judgemental tone.** Insights describe and suggest; they do not blame one partner.
7. **Designed for real relationships.** Couples start separately, merge, and sometimes separate. Either
   partner can end the shared household at any time, and no future partner ever sees a former partner's data.
   The app must not become a tool for financial surveillance.

## 5. Value proposition

- **For the participant:** snap a receipt, check two fields, done.
- **For the organiser:** categorisation happens automatically, budgets update in real time, monthly analytics
  are ready without spreadsheet work.
- **For the couple:** a shared dashboard and explainable AI insights ("Groceries are 18% above your 3-month
  average, mostly from 4 purchases at X") that make money conversations factual.

## 6. Success metrics

| Metric | Target (6 months after launch) |
|---|---|
| Households where **both** partners created ≥ 1 expense in the last 30 days | ≥ 60% of active households |
| Median time to record a manual expense | < 10 s |
| Field-level extraction accuracy on the golden dataset (total / date) | ≥ 97% / ≥ 95% |
| Error rate of **confirmed** receipt totals (monthly sample audit + later user corrections) | < 0.5% |
| Correct receipts wrongly flagged (false-positive flag rate) | < 10% |
| Category suggestions accepted without change | ≥ 80% |
| Households with ≥ 1 monthly budget set | ≥ 50% of active households |
| 3-month retention (household level) | ≥ 40% |

## 7. Scope by phase

| Phase | Content |
|---|---|
| **MVP** | Accounts, household lifecycle (create, invite with approval, join with data, dissolve, archive export), shared and personal expenses (with refunds, transfers, multi-category expense items), categories, receipt upload + AI extraction + validation + mandatory review, automatic categorisation, per-user AI consent, budget per period and per category, expense history with filters, monthly analytics and charts. |
| **V1** | Savings goals, AI financial insights, notifications (budget thresholds), data export. |
| **V2** | Conversational financial assistant (read-only, grounded in computed data). |
| **Later / to evaluate** | Partner balance & settle-up (transfers are already recorded), custom split ratios, personal budgets, recurring expenses, income tracking, bank synchronisation (Open Banking), multi-currency, offline-first capture, web client. |

## 8. Non-goals

- Not a bank, payment app or investment tool. No money movement.
- Not an accounting tool (no double-entry bookkeeping, no tax reports).
- No automatic creation of expenses from AI output without human confirmation.
- No financial advice in a regulated sense (the assistant describes the household's own data; it does not
  recommend financial products).
