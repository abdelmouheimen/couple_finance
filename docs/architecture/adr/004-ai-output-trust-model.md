# ADR-004 — AI output trust model

- **Status:** Accepted
- **Date:** 2026-09-28

## Context

The v0.1 design validated AI receipt output and relied on user confirmation, and it kept numbers out of LLM-written
insights via placeholders. The review found gaps:

- **Moving parsing to deterministic code did not remove ambiguity**: `1.234`, `03/04/2026`, `$` have several valid
  readings; picking one by household locale silently produces 1000× or month/day errors.
- Consistency checks produced **false flags** (VAT-inclusive totals, negative discount/deposit lines) and gave **no
  protection** when a receipt had no line items.
- The success metric (receipts confirmed without edits) rewarded **rubber-stamping**; model confidence was uncalibrated.
- Placeholders guaranteed correct numbers but not correct **claims** ("more" vs "less", invented causes).

## Decision

1. **Transcribe, then parse with ambiguity detection.** The model returns strings as printed plus observed formats;
   the parser enumerates all valid readings and resolves only when all hints agree; otherwise `AMBIGUOUS` with
   candidates for the user (BR-RCP-05).
2. **Receipt-aware checks.** Tax mode (`INCLUSIVE`/`EXCLUSIVE`/`UNKNOWN`), signed typed line items, payment-line
   check, handwritten tips; totals without any corroboration are `NOT_CORROBORATED` (BR-RCP-04, -06, -07, -13).
3. **Meaningful confirmation.** The API requires explicit acknowledgement of total and date and a resolution for
   every non-`OK` field (BR-RCP-10). Confirmation is exactly-once (BR-RCP-15).
4. **Measure truth, not convenience.** Metrics: accuracy of *confirmed* data (sample audits, later corrections),
   false-flag rate, ambiguity recall. Confidence is used only after per-field calibration (BR-RCP-11).
5. **Insights: deterministic semantics.** Direction, comparison and main driver are computed in analytics; the LLM
   receives no values; output is verified for placeholders, digits, polarity and causal claims; any failure falls
   back to a fixed template (BR-AI-10). Rendering is per user locale.
6. **Assistant (V2)**: read-only tools, grounding check on numbers, tool-result text treated as untrusted data.

## Consequences

- Positive: the classes of silent monetary error identified are closed; flags stay rare enough to be taken seriously;
  insight text cannot contradict the data it describes.
- Negative: more complex parser and review UI; some receipts need an extra user tap (ambiguous fields); a golden
  dataset covering locales and receipt shapes is required before launch; insight wording is more constrained.

## Alternatives rejected

- **Let the model normalise amounts/dates**: moves arithmetic-like decisions into the LLM (BR-MON-08) and hides
  ambiguity.
- **Auto-correct inconsistent totals**: the system would invent money.
- **LLM free-text insights with post-hoc number check only**: does not catch wrong direction or causality.
