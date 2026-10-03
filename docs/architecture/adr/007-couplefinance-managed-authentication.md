# ADR-007 — CoupleFinance-managed authentication

- **Status:** Accepted
- **Date:** 2026-10-03
- **Resolves:** [security.md](../security.md) §11 question 1, [architecture.md](../architecture.md) §12 question 4
- **Details:** [security.md §3](../security.md), [database-schema.md §4](../database-schema.md), `api/openapi.yaml`
- **Drives:** AUTH-001 (#75), AUTH-002 (#76), AUTH-003 (#77)

## Context

Security.md §3 described in-house authentication as a proposal, and the choice between in-house authentication and an
external identity provider (IdP) was listed as an open question. The authentication implementation Issues need the
decision fixed, and CLAUDE.md §2 requires authentication-architecture decisions to be recorded in an ADR. The Tech
Lead has approved the decision below.

## Decision

1. **CoupleFinance manages its own users and authentication** (module `identity`). There is no external identity
   provider for the MVP.
2. **Endpoints.** The approved authentication endpoints are: register, verify-email, resend-verification, login,
   refresh, logout, logout-all and me (as merged by AUTH-001..003). The paths and schemas are the contract in
   `api/openapi.yaml`; this ADR does not duplicate them.
3. **Access token.** JWT signed with ES256, valid 15 minutes, claims `sub` (user id), `sid` (session id), `iat`,
   `exp`, `aud`. It carries **no household id**; membership is resolved per request.
4. **Refresh token and session.** Opaque 256-bit random value, stored only as a hash, 30-day sliding expiry, rotated
   on every use. One session is created per login. Logout revokes the session; logout-all revokes every session of
   the user.
5. **Rotation and reuse detection (30-second grace, option A).** Refresh tokens form a single logical chain per
   session; only hashes are stored. Presenting a token that was rotated less than 30 seconds ago (parallel refresh
   by the client) rotates forward again from the chain, so that **only the newest token remains valid**. Presenting a
   rotated token outside the window is reuse: the session is revoked. Rotation is decided by database guarantees
   (conditional update/row lock), not by timing assumptions in application code.
6. **Registration and verification lifecycle.** An account starts `PENDING_VERIFICATION` and becomes `ACTIVE` once the
   email is verified with a single-use token valid 24 hours, stored only as a hash and consumed atomically. A verified account is required before creating or
   joining a household. Registration and resend-verification responses are non-enumerating (same response whether
   or not the email exists). Registration fields and the default locale `fr-FR` follow the OpenAPI contract. Password
   policy: 10 to 128 characters. A breached-password check and password reset are **out of the first slice**.
7. **Password hashing:** Argon2id through Spring Security.
8. **Rate-limiting principles.** Authentication endpoints are limited per IP and per account. The values are
   deploy-time configuration per environment through the existing rate-limit mechanism; production limits are never
   hard-coded.
9. **Signing-key handling.** Signing keys are deployment secrets injected via configuration or a secret manager,
   never committed or embedded in the artifact. Local and test keys are explicit non-production configuration;
   production fails fast when the signing key is missing (ephemeral keys are local/test only).
10. **Email delivery abstraction.** The application depends on a port for sending authentication emails; vendor
    adapters live in `infrastructure`. A fake adapter is approved for development and tests. The production provider
    is chosen separately.

## Consequences

- Positive: full control of the session and token model and of the user lifecycle; no third-party dependency or
  data sharing for sign-in; consistent with the documented household and privacy model.
- Negative: CoupleFinance owns credential storage, abuse protection and email deliverability; no social login or SSO
  convenience at launch.
- Access tokens remain valid up to 15 minutes after revocation (accepted, see security.md §3); sensitive endpoints
  additionally check that `sid` is not revoked.
- Accepted trade-off: inside the 30-second grace window a stolen just-rotated token also obtains a valid successor;
  the grace applies only to the immediately previous token of the chain.

## Deferred (not decided here)

MFA, passkeys, social login (Apple/Google), SSO, device management, signing-key rotation, progressive-delay
lockout, breached-password check, password reset.

## Alternatives rejected

- **External IdP for the MVP**: adds a vendor, cost and data-sharing review without being required to ship.
- **Refresh token stored in clear / JWT refresh tokens**: not revocable and a database leak would expose live
  credentials; opaque hashed tokens are stored instead.
- **Strict reuse revocation without a grace window**: breaks legitimate parallel refreshes from the mobile client.
