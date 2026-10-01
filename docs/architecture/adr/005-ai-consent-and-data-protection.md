# ADR-005 — AI consent and data protection

- **Status:** Accepted (redaction scope pending DPIA)
- **Date:** 2026-09-28

## Context

Receipt images and line items can reveal health-related purchases (pharmacy, clinic) — special-category data
under GDPR Art. 9 — as well as partial card numbers, loyalty ids and names. v0.1 used a household-level AI toggle,
meaning one partner could effectively consent for the other, sent line items for categorisation, and planned to
build an evaluation dataset from production receipts with loosely defined consent.

## Decision

1. **Per-user, explicit, opt-in consent**, recorded with the consent text version, for two purposes:
   (a) receipt analysis, (b) insights & assistant; plus a separate opt-in for (c) contributing receipts to the
   quality dataset. Withdrawable at any time, effective immediately including for queued jobs (BR-AI-05, BR-AI-09).
2. **Whose consent counts**: a receipt is analysed only with its **uploader's** consent (a). AI-worded household
   insights require **both** members' consent (b), otherwise fixed templates. The assistant requires the asking
   user's consent (b) and never accesses the partner's personal data.
3. **Minimisation**: categorisation sends only the normalised merchant name; insights send no values; no names,
   emails or identifiers are ever sent (BR-AI-06).
4. **DPIA before launch** covering receipt processing, providers and the partner-visibility model (BR-AI-08).
5. **Redaction step** reserved in the pipeline before the provider call (card numbers, loyalty ids, printed
   customer names, via local OCR). Whether it is mandatory at MVP is decided by the DPIA.
6. **Provider requirements**: DPA, no training on our data, zero/short retention, EU processing where available.
7. **Evaluation dataset**: synthetic and staff-donated receipts first; production receipts only under (c), after
   human-reviewed redaction, in separate storage with its own retention.
8. Consent is enforced twice: by the calling business module and by a guard in `ai.infrastructure` just before
   the provider call.

## Consequences

- Positive: lawful basis for special-category data; no partner consents on behalf of the other; smaller data
  footprint with providers; defensible dataset practice.
- Negative: onboarding includes consent screens; households with one non-consenting member get template insights;
  receipts of non-consenting users need manual entry; a local OCR component may be required.

## Alternatives rejected

- **Household-level toggle**: one partner decides for the other's data.
- **Legitimate interest as legal basis**: not available for special-category data.
- **Sending full line items for categorisation**: unnecessary for accuracy targets, avoidable health-data exposure.
