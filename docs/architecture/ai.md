# CoupleFinance — AI Architecture

Status: **Draft v0.2** — 2026-09-28 (revised after review; decisions in
[ADR-004](adr/004-ai-output-trust-model.md) and [ADR-005](adr/005-ai-consent-and-data-protection.md))

## 1. Principles

1. **AI proposes, deterministic code decides, humans confirm.**
2. **LLMs never compute money** (BR-MON-08). They transcribe what is printed and refer to computed facts via
   placeholders — nothing else.
3. **AI output is untrusted input**: schema-validated, business-validated, never executed.
4. **Ambiguity is surfaced, never guessed** (BR-RCP-05).
5. **Human confirmation must be meaningful**: we measure accuracy of *confirmed* data, not how few edits users
   make; total and date always require explicit acknowledgement (BR-RCP-10).
6. **Provider-agnostic**: business modules depend on ports in `ai.api`; provider adapters live in
   `ai.infrastructure`.
7. **Graceful degradation**: every AI feature has a non-AI fallback. An AI outage never blocks recording an expense.
8. **Consent-gated and minimal**: nothing is sent without the relevant user's consent (BR-AI-05), and only
   what the task needs (BR-AI-06).
9. **Measurable**: prompts versioned, calls logged, quality evaluated on a golden dataset.

## 2. Use cases

| Use case | Phase | Input | Output | Fallback |
|---|---|---|---|---|
| Receipt transcription | MVP | Redacted receipt images + hints | Structured, as-printed fields, formats observed, document type | Manual entry with images |
| Category suggestion | MVP | Normalised merchant name + list of active categories | One category id from the list + confidence | Merchant rules / "Other" |
| Insight wording | V1 | Typed facts (no values) + allowed phrasings | Short text with placeholders | Template text |
| Conversational assistant | V2 | Question + tool results | Answer grounded in tool results | Safe refusal |

## 3. Module design

```
business modules (receipt, categorization, insight, assistant)
          │ depend on
          ▼
ai.api  ── ports (interfaces) + provider-neutral DTOs
          │ implemented by
          ▼
ai.infrastructure
   ├── provider adapters (e.g. AnthropicAdapter, OpenAiAdapter, OcrApiAdapter, FakeAdapter)
   ├── PromptRegistry (versioned prompt templates)
   ├── Resilience (timeouts, retry, circuit breaker, concurrency limiter, per-household quotas)
   ├── AiCallLogger (ai.ai_call, metrics — no content)
   └── JSON Schema validation of raw model output
```

### Ports (`ai.api`)

| Port | Contract (provider-neutral) |
|---|---|
| `ReceiptExtractor` | `ExtractionResult extract(List<ReceiptPage> pages, ExtractionHints hints)` — raw strings for amounts and dates, observed formats, per-field confidence, document type, provider/model/prompt version. |
| `CategorySuggester` | `CategorySuggestion suggest(String normalisedMerchant, List<CategoryOption> options)` — one of `options` or none. |
| `InsightWriter` | `InsightText write(InsightFacts facts, Locale locale)` — text with `{{placeholder}}` tokens and the phrasing variant used. |
| `ChatModel` (V2) | Provider-neutral messages + tool calling. |

Rules:
- Port DTOs contain no provider types; provider SDKs only in `ai.infrastructure` (ArchUnit-enforced).
- **Consent is checked by the calling business module before invoking a port** and re-asserted by a guard in
  `ai.infrastructure` (the port call carries the consenting user id; the guard verifies the consent is still
  active — defence in depth).
- A `FakeAdapter` returns deterministic fixtures for local dev and tests.
- Provider selection per use case via configuration.
- **Outbound AI calls never run inside a database transaction** (see architecture.md §6.2).

## 4. Receipt transcription & validation

### 4.1 Pipeline

```
upload (1–5 images | PDF ≤ 5 pages)
  ─▶ pre-processing: re-encode, orientation, resize; PDF pages rasterised
  ─▶ redaction step (per DPIA, ADR-005): mask card numbers / loyalty ids   [see §7]
  ─▶ consent check (uploader, BR-AI-05)            ── no consent ──▶ manual draft
  ─▶ ReceiptExtractor (vision LLM or OCR API, JSON schema output)
  ─▶ [ai] JSON Schema validation  ── invalid ──▶ one repair retry ──▶ FAILED (manual retry ≤ 3, BR-RCP-17)
  ─▶ [receipt] deterministic parsing with ambiguity detection (§4.3)
  ─▶ [receipt] consistency checks (§4.4)
  ─▶ [receipt] duplicate detection (BR-RCP-14)
  ─▶ [categorization] suggestion per line item group / total (rules first)
  ─▶ Draft (visible to uploader only) with per-field status
  ─▶ user review: resolve every non-OK field, acknowledge total and date ─▶ confirm (exactly once) ─▶ expense
```

### 4.2 Extraction schema (logical)

```json
{
  "documentType": "RECEIPT | CARD_SLIP | INVOICE | QUOTE | REFUND_RECEIPT | OTHER",
  "merchantName": "string|null",
  "merchantCountry": "string|null (ISO 3166 if printed/inferable)",
  "language": "string|null (BCP 47)",
  "purchaseDate": "string|null (as printed)",
  "observedFormats": {
    "decimalSeparator": ". | , | unknown",
    "groupingSeparator": ". | , | space | ' | none | unknown",
    "dateOrder": "DMY | MDY | YMD | unknown"
  },
  "currencySymbolOrCode": "string|null (as printed)",
  "taxMode": "INCLUSIVE | EXCLUSIVE | UNKNOWN",
  "total": "string|null (as printed)",
  "handwrittenTotal": "string|null",
  "subtotal": "string|null",
  "taxLines": [{ "label": "string", "rate": "string|null", "amount": "string" }],
  "lineItems": [{ "type": "ITEM|DISCOUNT|DEPOSIT|DEPOSIT_RETURN|TIP|FEE|OTHER",
                  "description": "string", "quantity": "string|null", "amount": "string (signed as printed)" }],
  "payments": [{ "method": "CARD|CASH|OTHER", "amount": "string" }],
  "change": "string|null",
  "confidence": { "merchantName": 0.0, "purchaseDate": 0.0, "total": 0.0, "currency": 0.0 }
}
```

Strict mode: no additional properties, ≤ 300 line items, bounded string lengths. The model is instructed to
transcribe, not to normalise, convert or compute.

### 4.3 Parsing and ambiguity (deterministic)

- **Amounts**: the parser enumerates every valid reading of a string (e.g. `1.234` → 1234 or 1.234;
  `1,234` → 1234 or 1.234). It resolves to one reading only if all hints agree: `observedFormats`, other
  amounts on the same receipt (e.g. `12,50` elsewhere proves `,` is decimal), receipt language/country,
  currency decimals (BR-MON-03), household locale. Otherwise status `AMBIGUOUS` with the candidates.
- **Dates**: same approach (`03/04/2026` → 3 April or 4 March). Candidates outside BR-RCP-08 bounds are
  dropped; if more than one remains and hints disagree → `AMBIGUOUS`.
- **Currency**: `€`, `£`, ISO codes are unambiguous; `$`, `kr`, `¥` are resolved only with consistent
  country/language hints; else `AMBIGUOUS`.
- **Sign**: line items keep their printed sign; `DISCOUNT` and `DEPOSIT_RETURN` must be ≤ 0, others ≥ 0,
  else `INVALID`. A negative total proposes kind `REFUND`.

### 4.4 Consistency checks (deterministic)

Tolerance = max(0.02 currency units, 1% of total), computed with `BigDecimal`.

| Check | Applies when | On failure |
|---|---|---|
| Σ signed line items = total | line items present | total `INCONSISTENT` |
| subtotal + Σ tax = total | `taxMode = EXCLUSIVE` | `INCONSISTENT` |
| Σ tax lines ≈ Σ (rate-derived tax) | `INCLUSIVE` with rates | tax lines `INCONSISTENT` (informational) |
| Σ payments − change = total | payments present | `INCONSISTENT` |
| handwritten total ≥ printed total | handwritten total present | total `AMBIGUOUS` (tip) — user confirms |
| none of the above available | — | total `NOT_CORROBORATED` (requires explicit acceptance) |
| Range BR-MON-07, date BR-RCP-08, currency BR-RCP-09 | always | `INVALID` / `STALE` / flagged |
| Confidence below calibrated per-field threshold | calibration done | `LOW_CONFIDENCE` |

Validation never alters a value to make it fit. Confirmed values are those submitted by the user,
re-validated with the manual-expense rules.

### 4.5 Review UX contract (enforced by API)

- `POST /receipts/{id}/confirm` must carry `acknowledgedTotal` and `acknowledgedDate` equal to the submitted
  values, and a resolution for every non-`OK` field; otherwise 422.
- The confirm request carries the draft `version` and an idempotency key; exactly-once per BR-RCP-15.

### 4.6 OCR alternative

A dedicated OCR/receipt API, or a hybrid (OCR text → LLM structuring), can implement `ReceiptExtractor`.
Chosen by benchmark on the golden dataset (accuracy on local formats, false-flag rate, cost, latency, residency).

## 5. Financial insights

### 5.1 Facts, not free text

`analytics` produces typed facts on **household scope only** (BR-SCP-01), never on PERSONAL data:

```
Fact(type=CATEGORY_CHANGE, category="Restaurants", current=Money(412.30 EUR),
     baseline=Money(298.10 EUR), deltaPct=38.3, direction=INCREASE,
     mainDriver=MerchantDriver("Chez Paul", share=0.52) | NONE, period=2026-09)
```

Direction (`INCREASE`/`DECREASE`/`STABLE`), comparison (`ABOVE`/`BELOW` budget) and driver (with the share it
represents; a driver is only set if share ≥ 40%) are **computed deterministically**. `insight` rules decide
which facts are worth surfacing.

### 5.2 Constrained wording and verification

The LLM receives: fact type, **qualitative fields** (direction, driver present or not), placeholder names —
**no values** — and the locale. It returns 1–2 sentences using placeholders only.

Server-side verification (any failure → template fallback):
- Only known placeholders; required placeholders present.
- **No digits** in free text, except digits that are part of provided labels (category or merchant names).
- **Polarity check**: the text is classified against a per-locale lexicon (increase/decrease/above/below
  words); the detected polarity must match the fact's `direction`/comparison. Causal wording ("because",
  "mainly due to") is allowed only when `mainDriver` is set and must reference `{{driver}}`.
- Length, language, forbidden terms (blame, investment advice).

Placeholders are then substituted with values formatted from the facts, **per reading user's locale**.
Insights are stored as facts + per-locale generated templates (generated lazily for each locale needed), so
partners with different languages each get their own wording (BR-AI-10, F13).

If either member has not consented to (b), no LLM call is made: fixed per-locale templates are used.

### 5.3 Insight types (initial)

Category change vs 3-period average · Budget at risk (deterministic projection) · Unusual single expense ·
Goal off track / overdue · Period summary · Positive reinforcement.

## 6. Conversational assistant (V2)

- Tool-calling loop with **read-only** tools backed by `analytics`: `getPeriodSummary`, `getSpendingByCategory`,
  `searchExpenses`, `getBudgetStatus`, `getGoalProgress`, `compare(periodA, periodB, dimension)`.
- Scope: household data + the **asking user's** personal data; never the partner's personal data. Scope is
  injected server-side; the model never supplies household or user ids.
- The model is instructed not to do arithmetic; missing computations become new tools.
- **Grounding check**: every number in the answer must match a value in the conversation's tool results
  (after normalisation); otherwise the answer is regenerated once, then replaced by a safe response.
- **Untrusted content in tool results**: merchant names and notes (possibly written by the partner) are
  wrapped as data with explicit delimiters, length-limited, and the system prompt states they are never
  instructions. With no write tools, injection impact is limited to answer content, which the grounding check
  constrains further.
- Requires the asking user's consent (b). Conversations: 30-day retention **[assumption]**, deletable.

## 7. Data protection

Decisions in [ADR-005](adr/005-ai-consent-and-data-protection.md).

| Use case | Sent to provider | Never sent |
|---|---|---|
| Receipt transcription | Redacted receipt images, currency/locale hints | Names, emails, ids, other expenses |
| Categorisation | Normalised merchant name, category names | Line items, amounts, notes |
| Insights | Fact types, qualitative fields, category/merchant labels, "partner A/B" roles | Values, real names, notes, personal expenses |
| Assistant | Question, bounded tool results | Credentials, partner's personal data, other households |

- **Consent**: per user, explicit, opt-in, two purposes, withdrawable (BR-AI-05). Withdrawal stops new calls
  immediately; in-flight jobs re-check consent before calling the provider.
- **Special-category data**: receipts may reveal health information (pharmacy, clinic). Covered by the DPIA
  (BR-AI-08) and the explicit consent wording.
- **Redaction**: the DPIA decides whether a redaction pre-processor (local OCR detecting card PANs, loyalty
  numbers, printed customer names) is mandatory at MVP. The pipeline reserves the step either way.
- **Provider**: DPA, no training on our data, zero/short retention, EU processing where available.

## 8. Reliability, cost and observability

- Timeouts: extraction 30 s, categorisation 3 s, insight 10 s; retry on transient errors only; circuit
  breaker per provider; **bounded concurrency** of outbound AI calls (semaphore/bulkhead), independent of
  virtual-thread count.
- Quotas per household **and per verified user** (receipts/day, assistant messages/day); global cost alert.
- `ai.ai_call` log (no content).
- Metrics: field accuracy of confirmed data (sample audits), false-flag rate, ambiguity rate per field,
  post-confirmation correction rate, category acceptance, insight fallback/dismissal rates, cost per household.

## 9. Evaluation & prompt management

- Prompts in versioned files; version recorded on each call and draft.
- **Golden dataset**: ≥ 300 receipts covering languages, countries, formats (decimal/date variants),
  tax-inclusive and exclusive, discounts/deposits, tips, multi-part, faded/rotated, card slips, invoices.
  Sources: synthetic, staff-donated, and production receipts only under BR-AI-09 (separate opt-in,
  human-reviewed redaction, separate storage, own retention).
- **Confidence calibration**: per-field thresholds derived from the dataset (reliability curves); until then,
  confidence is not used to mark fields `OK`.
- Nightly/manual evaluation: field accuracy, false-flag rate, ambiguity detection recall. A prompt/model change
  merges only without regression.
- Model upgrades are evaluated like dependency upgrades, then switched by configuration.

## 10. Open AI questions

1. Provider(s) and EU residency; cost ceiling per household per month.
2. Vision LLM vs OCR API vs hybrid (benchmark).
3. Is the redaction pre-processor mandatory at MVP (DPIA outcome)?
4. Keep receipt line items only as evidence (current decision) or promote them to item-level analytics later?
5. Launch languages for receipts and insights.
6. Fully local categorisation (rules/embeddings) to send nothing for that use case?
