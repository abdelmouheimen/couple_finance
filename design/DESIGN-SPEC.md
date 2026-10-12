# CoupleFinance — Design specification

Source of truth: `design/reference/a_wide_high_fidelity_ui_ux_design_mockup_collage.png`
(SHA-256 `146825ee4ad6cb6835226b55b151c91f53c669767af03b2a5d8997671ac68b12`, never modified).
Machine-readable tokens: [`tokens.json`](tokens.json), mirrored in code by
`mobile/src/shared/ui/theme/tokens.ts` (equality enforced by `tokens.test.ts`).
Screens: [`SCREEN-SPEC.md`](SCREEN-SPEC.md). Acceptance: [`ACCEPTANCE.md`](ACCEPTANCE.md).

## 1. Rules from the reference ("Règles de design pour Claude" / "À ne pas faire")

| Rule (reference)                                                                      | How it is applied                                                                                                                              |
| ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- |
| Respect the exact palette, typography, spacing, shapes, icons                         | `tokens.json` → `tokens.ts`; token lint test forbids literal colours, sizes, radii, font families, opacities and emoji outside the token files |
| Use the provided components; do not invent new styles                                 | One shared component set in `mobile/src/shared/ui` (§4) reused by every screen                                                                 |
| Keep the navigation: Accueil, Dépenses, +, Budget, Paramètres                         | `BottomNav` + custom tab bar in `app/(tabs)/_layout.tsx`                                                                                       |
| Respect French texts and labels                                                       | `mobile/src/shared/i18n/strings.ts` is French; reference labels reproduced verbatim                                                            |
| Same cards, buttons, filters, forms                                                   | 16 dp card radius, pill buttons/segments/chips, 12 dp field radius everywhere                                                                  |
| Dark mode only                                                                        | Single dark palette; `userInterfaceStyle: "dark"`; the OS scheme is not followed                                                               |
| Responsive mobile (iOS/Android), same margins                                         | 20 dp horizontal gutter on every screen; layouts flex to the window width                                                                      |
| No other icon style, no new components, no structural change, no unrequested elements | Outline 2-unit-stroke vector icon set only (§5); see §7 for the few additions required by approved product rules                               |

## 2. Colours (Design System panel, verbatim)

| Token          | Hex       | Use                                                                          |
| -------------- | --------- | ---------------------------------------------------------------------------- |
| Primary        | `#22C55E` | Active segment/chip, primary button, FAB, active tab, positive status, links |
| Primary Dark   | `#16A34A` | Reserved (pressed/strong primary)                                            |
| Background     | `#0B0F0E` | Screen background (the panel label reads "#0BFF0E", see A-01)                |
| Card           | `#111827` | Cards, fields, segmented track, navigation bar, sheets                       |
| Surface        | `#1F2937` | Progress/ring tracks, avatar, dividers, skeletons                            |
| Text Primary   | `#F9FAFB` | Primary text and icons                                                       |
| Text Secondary | `#9CA3AF` | Captions, subtitles, inactive tabs, placeholders                             |
| Border         | `#374151` | Field, chip and secondary-button outlines                                    |
| Success        | `#22C55E` | "Dans les clous"                                                             |
| Warning        | `#F59E0B` | "À surveiller" (≥ 80 %, BR-BUD-06)                                           |
| Error          | `#EF4444` | "Dépassé", validation errors                                                 |

Derived (not on the panel, see A-06/A-07): `onPrimary` = background colour for text on primary
(dark text on green, as in the reference buttons); category tile colours and the "other" chart colour
were sampled from the reference screens.

All text/background pairs used meet WCAG AA (contrast test in `tokens.test.ts`).

## 3. Typography — Inter

| Style                 | Size / line height | Weight   | Use                                                           |
| --------------------- | ------------------ | -------- | ------------------------------------------------------------- |
| H1                    | 32 / 40            | Bold     | Tab titles ("Dépenses", "Budget", "Paramètres"), hero amounts |
| H2                    | 24 / 32            | Semibold | Stat amounts, brand title "CoupleFinance" (bold)              |
| H3                    | 20 / 28            | Semibold | Section titles, modal titles                                  |
| Body                  | 16 / 24            | Regular  | Default text, list titles (medium)                            |
| Caption               | 14 / 20            | Regular  | Labels, subtitles, chips, segmented labels                    |
| Label (derived, A-05) | 12 / 16            | Medium   | Navigation labels, badges                                     |

Amounts use tabular figures. Text scales with the OS up to 200 %.
Fonts: `@expo-google-fonts/inter` (400/500/600/700), loaded at start-up; a load failure falls back to the
system font instead of blocking the app.

## 4. Components (shared, `mobile/src/shared/ui`)

| Component                                 | Reference                                       | Spec                                                                                  |
| ----------------------------------------- | ----------------------------------------------- | ------------------------------------------------------------------------------------- |
| `Button` primary                          | "Primary Button"                                | Pill, primary fill, dark semibold label; 48 dp (`size="lg"` 56 dp full-width CTA)     |
| `Button` secondary                        | "Secondary Button"                              | Pill, card fill, 1 dp border, white label                                             |
| `Button` outline                          | "Modifier le budget"                            | Pill, transparent, primary border and label                                           |
| `TextInput`                               | "Search…", "Input field"                        | 48 dp, card fill, 1 dp border, 12 dp radius, optional leading icon / trailing element |
| `MoneyInput`                              | "Montant 13,78 │ €"                             | Field with a bordered currency-symbol suffix; canonical string value                  |
| `Chip` / `FilterChip`                     | "Chip", "Chip Active", "Chip +"                 | Pill 44 dp; active = primary fill; filter chip with icon + chevron or count badge     |
| `Segmented`                               | Foyer/Personnel, Scanner/Manuel/Importer        | Pill track (card + border), active segment primary                                    |
| `Card`                                    | Cards everywhere                                | Card fill, 16 dp radius, 16 dp padding, flat (no shadow)                              |
| `ListRow` + `Divider`                     | Settings lists, "Title / Subtitle / 123,45 € >" | 48 dp min, icon/tile, title, subtitle, chevron                                        |
| `IconTile`                                | Category squares                                | 40 dp, 12 dp radius, category colour, white icon                                      |
| `ProgressBar`                             | Budget bars                                     | 8 dp pill track (surface), status colour fill                                         |
| `ProgressRing`                            | "1%", "96%"                                     | 88 dp ring, 10 dp stroke, centred label                                               |
| `StatusPill` / `Dot`                      | "● Dans les clous"                              | 10 dp dot + semibold status text                                                      |
| `IconButton`                              | Filter button, period arrows                    | 48 dp bordered square (12 dp radius) or plain                                         |
| `BottomNav`                               | "Navigation"                                    | 80 dp bar (card), 4 destinations + raised 56 dp primary FAB                           |
| `Screen` / `ScreenTitle` / `SectionTitle` | —                                               | Safe areas, 20 dp gutter, 12 dp vertical rhythm, centred modal title with close/back  |

## 5. Icons

Outline, 24-unit grid, 2-unit round stroke, rendered with `react-native-svg` (`theme/icons.ts`, Lucide
shapes, ISC). Set of the "Icônes" panel: home, list, chart, settings, camera, image, users, tag, calendar,
sliders, more, receipt; plus the glyphs used on the screens (search, chevrons, close, back, check, flash,
bell, shield, lock, help, info, category icons). No emoji or text glyph is used as an icon (enforced by
`tokens.test.ts`).

## 6. Spacing, radii, sizes

Spacing scale 0/4/8/12/16/20/24/32; screen gutter 20; vertical rhythm between blocks 12.
Radii: 8 (small), 12 (fields, tiles, icon buttons), 16 (cards), pill (buttons, chips, segments, FAB).
Touch targets ≥ 48 dp on Android (44 pt iOS). All values in `tokens.json` → `size`.

## 7. Recorded ambiguities and decisions

The reference is a raster mock-up; where it is ambiguous or conflicts with approved product rules the
choice below was made and is reported for the human reviewer. Nothing else was invented.

| Id   | Ambiguity                                                                                                                                        | Decision                                                                                                                                                                                                                                                                                             |
| ---- | ------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| A-01 | Background swatch label reads "#0BFF0E" (a bright green), while the swatch and every screen are near-black (sampled `#010B0A`–`#020D0B`).        | `#0B0F0E` (typo reading consistent with the sampled colour).                                                                                                                                                                                                                                         |
| A-02 | Status bar, notch and home indicator are device chrome of the mock-up.                                                                           | Not drawn; the OS draws them; safe areas respected.                                                                                                                                                                                                                                                  |
| A-03 | "Octobre 2026 >" on Home has a chevron but no defined action.                                                                                    | Opens the Budget tab, where the period navigation lives.                                                                                                                                                                                                                                             |
| A-04 | Avatars are photographs; the API exposes no picture.                                                                                             | Neutral avatar (users icon on surface).                                                                                                                                                                                                                                                              |
| A-05 | Navigation label size is not on the type scale.                                                                                                  | 12/16 medium ("Label" token).                                                                                                                                                                                                                                                                        |
| A-06 | Text colour on primary is not specified.                                                                                                         | `#0B0F0E` (as in the reference buttons; 9.3:1).                                                                                                                                                                                                                                                      |
| A-07 | Only 5 category colours are visible (Courses, Transport, Restaurants, Logement, Autres); 12 system categories exist (BR-CAT-01).                 | Others extend the same family (tokens.json `category`); custom categories use "other".                                                                                                                                                                                                               |
| A-08 | Percentages appear as integers ("1%", "7%"); the API returns one decimal (BR-MON-09, e.g. "6.7").                                                | Server value shown verbatim ("6.7%"); the device never rounds money-derived values.                                                                                                                                                                                                                  |
| A-09 | The legend on Home lists categories at 0 %; analytics only returns categories with spending.                                                     | Legend shows the server categories only.                                                                                                                                                                                                                                                             |
| A-10 | Expense rows show receipt thumbnails; there is no receipt API yet (F4).                                                                          | No thumbnail until receipts exist.                                                                                                                                                                                                                                                                   |
| A-11 | Receipt capture and AI analysis (F4/F5) have no backend.                                                                                         | Scan/Import panels are laid out per the design but disabled with an explicit "Bientôt disponible" message and a link to manual entry; the review screen is a presentational component shown only in the dev design preview (`/design-preview`). Nothing is simulated as working.                     |
| A-12 | Add-expense opens on "Scanner" in the reference.                                                                                                 | Opens on "Manuel" while capture is unavailable (flag `RECEIPT_CAPTURE_AVAILABLE`).                                                                                                                                                                                                                   |
| A-13 | "Catégorie suggérée" shows two chevrons.                                                                                                         | One chevron (duplicate treated as a rendering artefact).                                                                                                                                                                                                                                             |
| A-14 | Settings header subtitle "Foyer principal" is not an API field.                                                                                  | Shows the household info line (currency · timezone · period start).                                                                                                                                                                                                                                  |
| A-15 | Settings rows Profil, Règles de catégorisation, Notifications, Sécurité, Données & confidentialité, Aide & support, À propos have no screen yet. | Rows shown as designed; tapping says "Bientôt disponible" (no fake screen).                                                                                                                                                                                                                          |
| A-16 | The reference shows no sign-out, invitations, spending-over-time chart, list total or period filters beyond the chips.                           | Kept where an approved feature requires them: sign-out at the bottom of Settings, invitations under Settings › Foyer, the spending chart below "Dépenses récentes", period/category filters in the filter sheet. The list total was removed (not in the design; no rule requires it on that screen). |
| A-17 | BR-SCP-03 requires every total to carry its scope; the reference shows the scope only as the selected segment.                                   | The selected segment is the visible scope label; every amount also carries its scope in its accessibility label.                                                                                                                                                                                     |
| A-18 | Phone proportions of the mock-up (~0.39) differ from Android phones (Pixel 7: 0.45).                                                             | Fixed validation viewport 412 × 915 dp; vertical positions compared as order + rhythm, not absolute pixels.                                                                                                                                                                                          |
