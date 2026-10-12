# ADR-008 — Gemini as first document-intelligence provider

- **Status:** Proposed (decision of the Tech Lead on 2026-10-12, recorded with DOC-003; acceptance on merge)
- **Date:** 2026-10-12
- **Related:** ADR-004 (AI output trust), ADR-005 (consent and data protection), ai.md §3–§8, issue #91

## Context

ai.md §10 left the receipt-transcription provider open. The Tech Lead chose Google Gemini as the first provider,
behind a provider-neutral port, with these constraints:

- business modules must not depend on any vendor type (BR-AI-04, ArchUnit);
- ADR-005 §6 requires a DPA, no training on our data, zero/short retention and EU processing where available;
- production users' financial documents must never reach Gemini's **free tier** (whose terms allow use of
  submitted content to improve Google products) without an approved privacy configuration and user disclosure;
- the extraction contract stays the approved one (ai.md §4.2, ADR-004): the model transcribes as printed,
  deterministic code parses.

## Decision

1. **Port.** `ai.api.DocumentIntelligencePort` (provider-neutral DTOs, the ai.md §4.2 schema transcribed as
   strings). The receipt module is its only caller. Category suggestion is **not** part of this call: it stays a
   separate step on the normalised merchant name only (ADR-005 §3, BR-AI-06), done in DOC-004.
2. **Adapters** in `ai.infrastructure`: `GeminiDocumentIntelligenceAdapter` (REST `generateContent`, structured JSON
   output with a response schema, temperature 0) and `FakeDocumentIntelligenceAdapter` (deterministic fixture for
   local development and tests). Selected by configuration:
   `couplefinance.ai.document-intelligence.provider = fake | gemini` (default `fake`).
3. **Endpoints.**
   - **Production:** Vertex AI Gemini (`endpoint-mode: vertex`) in an **EU region** (`europe-*`), under the Google
     Cloud terms with the Cloud DPA: prompts and outputs are not used to train models; abuse-monitoring retention per
     the Vertex AI data governance terms.
   - **Development:** Gemini Developer API (`endpoint-mode: api-key`) with a **paid** (billing-enabled) project and
     **synthetic or staff-donated receipts only**.
4. **Privacy gate (fail-fast).** The Gemini adapter is only created when
   `couplefinance.ai.gemini.privacy-approved=true`; otherwise the application refuses to start with the Gemini
   provider. With `endpoint-mode: vertex` the location must be an EU region. With `endpoint-mode: api-key` the
   operator must also declare `couplefinance.ai.gemini.api-key-tier=paid`; `free` is always refused. The API key or
   service-account credentials come from the environment only and are never sent to the mobile app.
5. **Consent and minimisation** (ADR-005): the receipt module checks the uploader's receipt-analysis consent before
   queuing and again before each call; `ai.infrastructure` re-asserts it in a guard just before the HTTP call. Only
   the re-encoded page images and currency/locale hints are sent — no names, emails or ids.
6. **Reliability:** 30 s timeout, at most one schema-repair retry, bounded concurrency, automatic retries of
   transient failures by the receipt job (≤ 3, exponential backoff), explicit failure states (BR-RCP-17). Every call
   is logged in `ai.ai_call` with metadata only (BR-AI-07).

## Consequences

- Positive: provider switch by configuration only; contract tests run against both adapters; no vendor SDK in
  business code (plain HTTP, no Gemini SDK dependency).
- Negative: Vertex AI requires a Google Cloud project, billing and service-account credentials
  (`google-auth-library` in `ai.infrastructure` only); the redaction step and the DPIA (BR-AI-08) are still
  prerequisites for a production launch; prompt/model changes require the golden-dataset evaluation (ai.md §9).

## Alternatives rejected

- **Gemini free tier with production data** — terms allow product improvement on submitted content; incompatible
  with ADR-005 §6.
- **Vendor SDK in the receipt module** — violates BR-AI-04.
- **Model-suggested category from the image** — sends more than the merchant name for categorisation (ADR-005 §3).
