# CoupleFinance — mobile app

React Native + TypeScript (strict) on **Expo** (managed workflow + dev builds) with Expo Router, TanStack Query,
React Hook Form + Zod (UX validation only), `expo-secure-store`, and an API client generated from
[`../api/openapi.yaml`](../api/openapi.yaml). See [architecture.md §9](../docs/architecture/architecture.md#9-mobile-architecture-react-native--typescript).

## Getting started

Requires Node 22+.

```bash
cd mobile
npm install        # .npmrc sets legacy-peer-deps (Expo's optional peers conflict under npm's strict resolver)
npm run start      # Expo dev server; press i (iOS simulator) or a (Android emulator)
```

## Scripts

| Script                                                 | Purpose                                                                      |
| ------------------------------------------------------ | ---------------------------------------------------------------------------- |
| `npm run generate:api`                                 | Regenerate `src/shared/api/generated/schema.d.ts` from `../api/openapi.yaml` |
| `npm run check:api`                                    | Regenerate and fail if the committed output differs (CI drift check)         |
| `npm run lint` / `format:check` / `typecheck` / `test` | Quality gates (all run in CI); `npm run verify` runs them together           |

The generated client types are **never hand-edited**: change the backend, run `./gradlew updateOpenApi` in
`backend/`, then `npm run generate:api` and commit both.

## Configuration

Public, non-secret values only, via Expo public env vars (inlined into the bundle, so **never put secrets there**):

| Variable                   | Default                 | Meaning                                                                                                                                                                                                                                   |
| -------------------------- | ----------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `EXPO_PUBLIC_API_BASE_URL` | `http://localhost:8080` | Backend origin (no `/api/v1` suffix). Android emulator: `http://10.0.2.2:8080`. `http` is accepted only for localhost, 127.0.0.1, 10.0.2.2 and, in development bundles only, private LAN IPv4 addresses; everything else must be `https`. |

Copy `.env.example` to `.env.local` (git-ignored) per environment.

### Physical phone (Expo Go, same Wi-Fi)

From the repository root: `powershell.exe -ExecutionPolicy Bypass -File .\scripts\start-mobile-local.ps1`
(add `-Tunnel` when the LAN QR code is unreachable). It starts PostgreSQL and the backend, detects the PC's LAN IP,
sets `EXPO_PUBLIC_API_BASE_URL=http://<LAN IP>:<port>` for Expo only and shows the QR code to scan with Expo Go.

## Layout

```text
app/                 Expo Router routes (placeholder home only)
src/features/<name>  feature folders mirroring backend modules
src/shared/api       generated client, typed Problem Details errors (ApiError.code)
src/shared/money     string/minor-unit money utility (no arithmetic, BR-MON-02)
src/shared/config    typed environment configuration
```

## Rules (CLAUDE.md §14)

- API types come from the generated client; never hand-written.
- No money arithmetic or aggregation on the device; amounts stay strings / integer minor units (BR-MON).
- The refresh token lives in secure storage (`expo-secure-store`) only — never AsyncStorage or logs.
- Branch on `ApiError.code`, never on message text. Client validation is UX only.
- No `any` without a justified, scoped `eslint-disable`.

## Design system and visual validation (MOBILE-009)

The UI follows the approved design in [`../design/`](../design): reference image (never modified), tokens
(`design/tokens.json`, mirrored by `src/shared/ui/theme/tokens.ts`), `DESIGN-SPEC.md`, `SCREEN-SPEC.md` and
`ACCEPTANCE.md`. Dark theme only, Inter, French labels, vector icons (`src/shared/ui/theme/icons.ts`).

Visual check in the fixed Android reference viewport (Pixel 7, 412 × 915 dp), with API fixtures served by
the harness (the app ships no sample data):

```bash
npx expo start --web --port 8081     # terminal 1
node scripts/visual/capture.mjs      # terminal 2 -> ../design/screenshots (exit 1 if a criterion fails)
```

On web the refresh token is kept in memory only (`tokenStorage.web.ts`); `/design-preview` (dev builds
only) renders the receipt screens, which have no backend yet.
