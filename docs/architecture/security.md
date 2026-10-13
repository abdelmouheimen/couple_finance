# CoupleFinance — Security Architecture

Status: **Draft v0.2** — 2026-09-28 (revised after review)

CoupleFinance stores financial behaviour, receipt images (which may show card fragments, names, health-related
purchases) and personal data of two people in a relationship. It moves no money but is a high-value privacy
target — including **from inside the household** (a partner or former partner).

---

## 1. Threat model (summary)

| Asset | Threats | Key mitigations |
|---|---|---|
| Household financial data | Cross-household access (IDOR), account takeover, log leaks | Household scoping (§4), strong auth (§3), log hygiene (§8) |
| Personal expenses | Partner reading the other's personal spending | Owner-only visibility enforced in queries (§4.2), no leakage via analytics, notifications, rules, audit |
| Former partner's data | New partner inheriting history; ex keeping access | Dissolution model (ADR-002): no joins into dissolved households, read-only archive, no copy of SHARED data |
| Household membership | Intercepted invitation link giving full history | ≥ 128-bit codes, rate-limited redemption, **inviter approval** (BR-HH-04) |
| Receipt images | Public bucket, guessable URLs, GPS metadata, card numbers | Private storage, 5-min presigned URLs, re-encoding, redaction step (ADR-005) |
| Credentials & tokens | Credential stuffing, device theft, refresh replay | Argon2id, rate limits, secure storage, rotation + reuse detection |
| AI pipeline | Prompt injection, over-sharing with provider, malicious output | Untrusted-output handling, consent gates, minimisation (§7, ai.md) |
| Coercive control | App used to monitor a partner | Private personal expenses, unilateral dissolution, no lock-screen amounts, neutral insights |
| Availability / cost | AI endpoint abuse, multi-account quota farming | Per-household and per-verified-user quotas, upload limits |

Verification target: OWASP ASVS Level 2 (backend), OWASP MASVS (mobile).

## 2. Transport

- TLS 1.2+ (1.3 preferred); HSTS on any web endpoint.
- Certificate pinning: not in MVP; revisit after launch.
- No secrets in URLs; invitation deep links carry only a single-use code.

## 3. Authentication

**Decision (MVP):** CoupleFinance-managed authentication with Spring Security, no external IdP
([ADR-007](adr/007-couplefinance-managed-authentication.md)).

| Element | Design |
|---|---|
| Registration | Email + password; verified email required before creating/joining a household. Registration responses do not reveal whether an email is already registered (confirmation email sent in both cases). |
| Password storage | Argon2id; breached-password check (k-anonymity) **[proposal]**; min 10 chars. |
| Access token | JWT signed with **ES256** (EdDSA may be added later), **15 min**, claims `sub`, `sid`, `iat`, `exp`, `aud`. **No household id** — membership resolved per request. |
| Validation (implemented) | Resource server with keys from a JWKS endpoint (`couplefinance.security.jwt.jwk-set-uri`; none configured = the public part of the signing key is trusted). Required: valid ES256 signature, `exp` present and not past (60 s skew), `sub` = user id, `aud` contains `couplefinance-api`, `iss` when configured. The user must exist and not be deleted (else 401) and be `ACTIVE` (else 403 `EMAIL_NOT_VERIFIED`). Issuance (`POST /api/v1/auth/login`, Issue #75): ES256 signed by `couplefinance.security.jwt.signing-jwk` (deployment secret, private P-256 JWK; unset = ephemeral key, local only), 15 min, claims `sub`, `sid` (session id), `iat`, `exp`, `aud`, `iss` when configured, no household id. A session and a first refresh token (opaque 256-bit, SHA-256 stored, 30 days) are created; passwords are verified with Argon2id; unknown email, wrong password and deleted account are indistinguishable (`INVALID_CREDENTIALS`). Refresh/logout: not implemented yet. |
| Revocation window | Access tokens stay valid up to 15 min after logout-all / password reset / account deletion (accepted). **Sensitive operations** additionally require a **recent authentication** (§3.1). |
| Refresh token | Opaque 256-bit, stored hashed, 30-day sliding, rotated on each use. **Reuse detection with a 30 s grace window**: presenting a token rotated less than 30 s ago rotates forward again in the same chain so only the newest token stays valid, instead of revoking (handles parallel refreshes; ADR-007). Reuse outside the window revokes the session. |
| Client | **Single-flight refresh** in the app: concurrent 401s wait on one refresh call. |
| Logout | Revokes the session; "log out everywhere" revokes all sessions. |
| Password reset | **Deferred (ADR-007).** Single-use hashed token, 30 min; all sessions revoked. No account enumeration. |
| Brute force | Rate limits per IP and account on login, reset, verification and invitation redemption; no permanent lockout; progressive delays deferred (ADR-007). |
| Biometrics | Local app unlock only. |
| MFA | Not in MVP; TOTP or passkeys in V1. |

### 3.1 Recent authentication for sensitive operations

([ADR-009](adr/009-household-join-journey.md) §2.) A sensitive operation requires (1) the token's `sid` to be an
existing session of the caller, not revoked and not expired, **and** (2) that session to have been created by a
credential login less than `couplefinance.security.recent-auth-window` ago (default `PT15M`).
`identity.session.created_at` is the instant of the last effective authentication of the session: login verifies the
password; token refresh never changes it. Re-authentication is an explicit login (a new session); the mobile app then
revokes the superseded session. No new credential flow (ADR-007).

| Failure | Response |
|---|---|
| Session missing, revoked or expired | 401 `SESSION_REVOKED` |
| Session valid but older than the window | **403** `REAUTHENTICATION_REQUIRED` (403 rather than 401, so the single-flight refresh does not loop) |

Sensitive operations: join approval; archive export and archive document download; redemption by a requester who owns
an `ACTIVE` household; dissolution; single-member household deletion; account deletion (consent changes when
implemented). True step-up / MFA is V1.

## 4. Authorisation

### 4.1 Household isolation

1. **The household is derived from the authenticated user**, never from the client. `household.api` exposes
   `HouseholdContext currentHousehold()` → `{householdId, userId, status, role: MEMBER | ARCHIVE_READER}`.
2. Every use case receives a `HouseholdContext`; every repository query on household data is scoped by
   `household_id`. Repositories expose only scoped methods (`findByIdAndHouseholdId`); ArchUnit enforces it.
   The audit history of an entity moved by a merge is **not** an exception: it is read only after the entity is
   authorised in the caller's context, and only from the caller's household and the origin household that the caller's
   own household recorded for that entity (database-schema.md §11.1, ADR-009 §9); a client never supplies a household
   id or a provenance, and an entity id alone opens nothing. The GA RLS policy of audit tables expresses the same rule.
3. **All** household tables carry `household_id`, including child tables (database.md §1).
4. Another household's resource → **404**.
5. UUIDv7 identifiers.
6. Test matrix generated from the OpenAPI spec: every endpoint called by a user of household B on household A's
   resources returns 404. Implemented by `platform.authz.AuthorizationMatrixIntegrationTest`: every operation
   must be registered in `OperationRegistry` (an unclassified operation fails the build), so each new endpoint
   extends the matrix as part of its Definition of Done.
7. Defence in depth: **PostgreSQL Row-Level Security** on household tables with `app.household_id` and
   `app.user_id` set per transaction — **decided for GA**, optional during MVP development
   (open question: performance validation).

### 4.2 Partner privacy inside a household

- PERSONAL data (`owner_user_id` set) is visible only to its owner: expenses, their items, receipts,
  drafts, audit rows, user-level merchant rules (BR-EXP-07, BR-CAT-05, BR-RCP-16).
- Scoped query predicate: `household_id = :h AND (owner_user_id IS NULL OR owner_user_id = :u)`; RLS policy
  mirrors it.
- Indirect leak paths closed by rule: household analytics and budgets exclude PERSONAL (BR-SCP-01); insights
  use household facts only; notifications never mention PERSONAL data (BR-NOT-02); duplicate detection only
  compares against expenses the uploader can see; household merchant rules never learn from PERSONAL expenses.
- Cross-user privacy tests: for each endpoint, member B must not observe member A's PERSONAL data — including
  via totals, counts, search results and error messages.

### 4.3 Household lifecycle authorisation

- Joining requires a valid invitation **and** approval by the inviting member (BR-HH-04). Until approval,
  the requester has no access.
- A `DISSOLVED` household is read-only: `ARCHIVE_READER` role allows GET and export only, until
  `household_member.archive_access_until` (BR-HH-10, BR-HH-19).
- **Archive routes** `/api/v1/archives` and `/api/v1/archives/{archiveId}/...`: `household.api` resolves an
  `ARCHIVE_READER` context only if the caller has a membership of that household with `left_at` set and
  `archive_access_until > now`; otherwise **404** (no disclosure). No write route exists; the routes do not alter the
  resolution of the caller's active household. Archive readers: former members of a household left by dissolution; after
  a join, the **joiner only** (the partner never reads the joiner's archived S); none after a full merge (ADR-009 §4).
  CSV export protects against formula injection. Documents are retrievable per page through 5-minute presigned URLs
  issued after the archive check, and as a bulk ZIP of the re-encoded page images; original PDFs are never served back
  (§6). Export and document download require recent authentication (§3.1).
- **Join request.** Until approval nothing financial is visible to anyone (BR-HH-18); the requester only sees the target
  name and the inviter's display name. The code is consumed at redemption; approval is limited to the invitation
  creator; consulting and refusing to any active member of the target. Redemption by an owner of an `ACTIVE`
  household, dissolution, single-member deletion, account deletion and join approval require recent authentication
  (§3.1).
- **Write guard.** Mutating use cases take `FOR KEY SHARE` on their household row and re-read its status; lifecycle
  transitions take `FOR UPDATE`, so a write cannot land in a household dissolved or merged concurrently (ADR-009 §6).

### 4.4 System (non-user) execution

Background jobs, schedulers and event listeners have no user token. They run with a
`SystemHouseholdContext(householdId, reason)` built from the job's stored `household_id`; audit actor =
`SYSTEM:<job>`. Only purge jobs may use unscoped repository methods, isolated in dedicated classes and allowed by
an explicit ArchUnit exception. Deleting the origin audit rows of a moved entity (another household id than the one
being purged) is part of those purge classes and of nothing else (database-schema.md §11.1).

## 5. Input validation & API hardening

- Bean Validation on request DTOs; domain validation in aggregates; unknown JSON properties rejected.
- Money parsed strictly from strings (BR-MON-02/03).
- Request limits: JSON 64 KB; upload 10 MB per file, 25 MB per receipt; timeouts everywhere.
- Rate limiting (Bucket4j): per IP, per user; stricter on auth, invitation redemption, receipt upload.
- Idempotency (BR-EXP-13) prevents duplicate writes under retries.
- Problem Details errors with no stack traces, SQL, or hints about other users' data.
- CORS disabled (mobile only). `Cache-Control: no-store` on authenticated responses.
- Dependency, SAST and container scans in CI.

## 6. Receipt files

- Content type from magic bytes (signature check in `receipt.domain.DocumentFormat`; no Tika needed for four
  formats); JPEG/PNG/PDF accepted. HEIC is recognised and refused by the API (no safe server-side decoder): the app
  converts photos to JPEG before upload (DOC-001; pending Tech Lead confirmation against BR-RCP-01).
- Images re-encoded (drops EXIF/GPS and embedded payloads); PDFs rasterised; original PDF never served back.
  Decoding is bounded: dimensions read before pixels (≤ 12 000 px per side, ≤ 50 Mpx), encrypted PDFs refused,
  stored pages ≤ 3 000 px long side.
- Private bucket; key = random UUID (no user or household data); server-side encryption.
- Served via **presigned GET URLs valid 5 minutes**, generated after the household **and visibility** checks.
  Presigned URLs are never logged or sent to crash reporting.
- `original_sha256` (upload bytes) and perceptual hash stored for duplicate detection (BR-RCP-14).
- Malware scanning of every upload before decoding or storage (ClamAV `clamd` over TCP, DOC-001). Fail-closed: an
  unreachable scanner or an error answer refuses the upload (503); nothing is stored for a rejected file.
- Every upload and deletion is audited in `receipt.audit_event` (metadata and hashes only, never content or keys).
- Redaction step before AI per ADR-005.

## 7. AI-specific security

(See also [ai.md](ai.md#7-data-protection).)

- **Consent gates** (BR-AI-05): checked by the calling module and re-checked by a guard in `ai.infrastructure`
  immediately before each provider call; consent withdrawal takes effect for in-flight jobs.
- **Prompt injection**: extraction, categorisation and insight wording have no tools and no side effects; worst
  case is a wrong draft/text, caught by validation, mandatory review and insight verification.
- **Assistant (V2)**: read-only, server-scoped tools; untrusted text in tool results (merchant, notes — possibly
  written by the partner) delimited and length-bounded; grounding check on numbers.
- Output parsed against strict schemas; free text length-limited and escaped at render.
- Data minimisation per ai.md §7; no identifiers sent.
- Provider DPA, zero/short retention, no training on our data, EU processing where available.
- Provider keys in a secret manager; the mobile app never calls AI providers.

## 8. Data protection & privacy (GDPR)

| Topic | Design |
|---|---|
| Legal basis | Contract for core features. **Explicit, per-user consent** for AI processing (Art. 6(1)(a) and Art. 9(2)(a) for health-related data possibly visible on receipts) — ADR-005. |
| DPIA | Mandatory before launch (BR-AI-08): receipt processing, AI providers, partner-visibility model. |
| Encryption at rest | Managed DB and storage encryption. Column-level encryption for notes/merchant not in MVP. |
| Secrets | Secret manager; none in repo or images. |
| Logs | Pseudonymous ids only; no amounts with merchant and user in the same line; no receipt content, tokens, prompts/outputs, presigned URLs. |
| Retention & erasure | BR-DAT table (business-rules.md §11): field-level policy for deletion and dissolution, audit redaction, tombstoned user rows. |
| Data subject rights | Export (own personal data + household shared data), rectification, erasure (BR-HH-15, BR-DAT). |
| Evaluation dataset | Separate opt-in, human-reviewed redaction, separate storage and retention (BR-AI-09). |
| Backups | Encrypted, 30-day retention; erasure effective when backups expire (documented in privacy notice). |
| Product analytics / crash reports | No financial values, no receipt content; opt-out; PII scrubbing. |

## 9. Mobile app security

- Refresh token in Keychain/Keystore (`expo-secure-store`); access token in memory only.
- No financial data persisted unencrypted on device (in-memory query cache only) **[revisit if offline mode]**.
- Privacy screen in the app switcher.
- Push notifications without amounts by default (BR-NOT-01); no PERSONAL data in notifications (BR-NOT-02).
- Receipt images deleted from app cache after upload; not saved to the gallery.
- Release builds: no debug logs.
- Deep links validated; invitation link only starts a join request.

## 10. Audit & monitoring

- Audit trail for expense, budget, category, membership, dissolution, merge, consent changes.
- Security alerts: login failure bursts, refresh reuse outside grace window, invitation redemption bursts,
  cross-household 404 spikes, upload rejections, AI cost anomalies.
- Production data access: none by default; break-glass procedure with audit **[to define]**.

## 11. Open security questions

1. ~~In-house auth vs external IdP~~ — **resolved**: in-house, no IdP at MVP ([ADR-007](adr/007-couplefinance-managed-authentication.md)); Sign in with Apple / Google deferred.
2. RLS: confirm performance and adopt from the first release, or at GA?
3. Is column-level encryption required for notes/merchants?
4. Is the redaction pre-processor mandatory at MVP (DPIA outcome)?
