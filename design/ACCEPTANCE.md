# CoupleFinance — Design acceptance

## Reference viewport

Google Pixel 7 (Android): **412 × 915 dp**, device scale factor 2, locale `fr-FR`, time zone
`Europe/Paris`, clock frozen at 2026-10-12 10:00. Rendering: Expo web in headless Chrome (Playwright).
API responses come from `mobile/scripts/visual/fixtures.mjs` (the figures of the reference); the app itself
contains no sample data.

```bash
cd mobile
npx expo start --web --port 8081          # terminal 1
node scripts/visual/capture.mjs           # terminal 2: screenshots, comparisons, checks (exit 1 on failure)
```

Outputs in `design/screenshots/`: `NN-<screen>.png`, `compare-NN-<screen>.png` (reference crop on the
left, implementation on the right) and `report.json` (anchors, colour samples, check results).
The reference image is read only and is never regenerated or modified.

## Automated, measurable criteria (all must pass)

| Id    | Criterion                                                                                                                                       |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| AC-01 | Rendered background, card and primary colours equal `tokens.json` exactly (pixel samples).                                                      |
| AC-02 | No runtime error or console error while visiting every screen.                                                                                  |
| AC-03 | On every tab screen the navigation bar is 80 dp high and docked to the bottom edge.                                                             |
| AC-04 | The central add action is 56 × 56 dp and horizontally centred.                                                                                  |
| AC-05 | Block order of each screen matches `SCREEN-SPEC.md` (vertical anchors strictly increasing).                                                     |
| AC-06 | Titles, segmented control and search field start on the 20 dp gutter.                                                                           |
| AC-07 | The segmented control spans the content width (viewport − 2 × 20 dp).                                                                           |
| AC-08 | The primary call to action is 56 dp high.                                                                                                       |
| AC-09 | Code tokens equal `tokens.json`; no literal colour, size, radius, font family, opacity or emoji outside the token files (`tokens.test.ts`).     |
| AC-10 | Text/background pairs meet WCAG AA; chart and category colours ≥ 3:1 on cards (`tokens.test.ts`).                                               |
| AC-11 | Navigation: four destinations in order, the selected one exposed as selected, add action reachable from every tab (`__tests__/shell.test.tsx`). |

## Reviewed visually (reference vs implementation)

For each screen: geometry (block sizes and rhythm), typography (scale and weights), spacing, colours,
icons and content are compared on `compare-*.png`. Remaining differences are listed in the PR
"Design compliance report" and in `DESIGN-SPEC.md` §7 (A-01 … A-18).

## Functional criteria

- `npm run verify` (lint, format, type check, tests) green in `mobile/`.
- Monetary values are never converted to JS numbers or computed on the device; `formatMoney` keeps the
  Hermes BigInt fix (`money.test.ts`).
- Loading (skeleton), empty and error states exist for every data block.
- Safe areas respected (status bar, home indicator, modals with bottom edge).
- Every interactive element has an accessible name and a ≥ 48 dp touch target on Android.
