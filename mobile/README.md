# CoupleFinance — mobile app

Not initialised yet. The app will be React Native + TypeScript (strict), as described in
[architecture.md §9](../docs/architecture/architecture.md#9-mobile-architecture-react-native--typescript).

Open decision before scaffolding: **Expo (managed workflow + dev builds) vs bare React Native** (architecture.md
§12, question 8).

Rules that apply from the first commit (CLAUDE.md §9):

- the API client is generated from [`../api/openapi.yaml`](../api/openapi.yaml) — never hand-written;
- no money arithmetic or aggregation on the device; amounts stay strings / integer minor units;
- the refresh token lives in secure storage only.
