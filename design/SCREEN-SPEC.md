# CoupleFinance — Screen specification

Common to every tab screen: background `#0B0F0E`; 20 dp gutter; 12 dp between blocks; H1 title at the top;
bottom navigation (80 dp, card colour, hairline top divider) with Accueil · Dépenses · **+** · Budget ·
Paramètres; active tab = primary icon + semibold label; the central add action is a raised 56 dp primary
circle that opens "Ajouter une dépense" (disabled for a dissolved household, BR-HH-10). Modal screens
(add expense, review) have a header row: close ✕ or back ← on the left, H3 title centred.
Every figure comes from the API verbatim (BR-ANA-01, BR-MON-08); amounts use `formatMoney` (exact string,
Hermes-safe).

Responsive behaviour: widths flex with the window; two-column stat pairs stay side by side; the category
donut and its legend wrap under each other below ~360 dp; long texts truncate with an ellipsis; content
scrolls vertically; text scales up to 200 %. Loading = skeletons in place of each block; errors =
inline error state with "Réessayer"; empty = empty state with the next action.

## 1. Accueil (Home) — `src/features/analytics/Dashboard.tsx`

1. Header row: "CoupleFinance" (H2 bold) + avatar (40 dp, A-04).
2. Period title "Octobre 2026 ›" (body semibold, chevron; opens Budget, A-03).
3. Segmented Foyer / Personnel (full width).
4. Card "Budget mensuel": caption title; remaining (H1); "sur {limit}" (secondary); status pill; right:
   88 dp ring with the consumption percentage. HOUSEHOLD without budget → "Définir un budget" (outline);
   PERSONAL → no budget card (BR-BUD-02).
5. Two cards side by side: "Dépensé" {total} + "{x}% vs {previous month}"; "Reste" {remaining} +
   "{x}% du budget utilisé".
6. "Répartition par catégorie" (H3): 160 dp donut (28 dp stroke) with total + "Total" in the centre; legend
   card (dot, name, amount, percentage); max 5 slices + "Autres" (chart only). Refund categories listed
   apart (BR-ANA-06). Tap → expense list filtered by category.
7. "Dépenses récentes" (H3) + "Voir tout" (primary link): up to 5 expense cards (tile, merchant,
   "date · category", amount).
8. "Évolution des dépenses" chart (not in the reference, A-16).

## 2. Dépenses — `src/features/expense/ExpenseListScreen.tsx`

1. "Dépenses" (H1).
2. Search field (search icon, "Rechercher un commerçant...") + 48 dp filter icon button.
3. Segmented Foyer / Personnel.
4. Horizontal chips: period ("Octobre 2026 ⌄", calendar icon), "Catégories" (layers icon, count badge
   when filtered), active category chip with ✕ to remove it.
5. List grouped by date (caption headers "4 oct. 2026"); each row is a card: 40 dp category tile, merchant
   (medium), category (secondary), amount (semibold, right). Personal expenses carry a "Personnel" badge
   (only in the personal view, BR-EXP-07). Cursor pagination; pull to refresh.
6. Filter sheet: period (période en cours / tout l'historique / personnalisée + dates), categories.

## 3. Ajouter une dépense — `src/features/expense/QuickAddScreen.tsx`

1. Header: ✕ + "Ajouter une dépense".
2. Segmented Scanner / Manuel / Importer.
3. Scanner / Importer (`src/features/receipt/ReceiptCapturePanel.tsx`): viewfinder card with four primary
   corner brackets; row gallery (56 dp round) · shutter (72 dp ring) · flash (56 dp round); helper
   "Prenez une photo du ticket" / "Le reçu sera analysé automatiquement". While receipt capture is
   unavailable (A-11) the controls are disabled and the helper reads "Bientôt disponible" + "Saisie
   manuelle".
4. Manuel: amount (`MoneyInput`, € suffix), category chips, suggestion, "Plus d'options" (date, merchant,
   note, sharing), full-width "Enregistrer" (56 dp). Defaults and idempotency unchanged (BR-EXP-01/13).

## 4. Vérifier les informations — `src/features/receipt/ReceiptReviewScreen.tsx` (design only)

1. Header: ← + "Vérifier les informations".
2. Receipt photo (96 dp) + secondary button "Changer la photo" (camera icon).
3. Fields (label above, 48 dp box): Commerçant (tile + name + ✓), Date (calendar + date + ✓), Montant
   (value │ €), Catégorie suggérée (tile + name + ⌄).
4. Card "Confiance de l'analyse": 88 dp ring (overall) + 4 rows (dot, label, percentage).
5. "Lignes du ticket (optionnel)" + row "{n} articles détectés ›".
6. Full-width primary "Créer la dépense" (56 dp).
   Presentational only, rendered in `/design-preview` (dev builds); persistence will follow BR-RCP-02/10/15.

## 5. Budget — `src/features/budget/BudgetScreen.tsx`

1. "Budget" (H1).
2. Period row: ‹ bordered icon button · "Octobre 2026" (centred, semibold) · › (disabled for the current
   period: the future is not browsable).
3. Card "Budget global": remaining (H1) + "sur {limit}"; 8 dp progress bar; "{x}% utilisé" (percentage in
   status colour) and the status pill right-aligned.
4. Two cards: "Dépensé" / "Reste" (H2 amounts).
5. "Par catégorie" (H3) + one card with divided rows: tile, name, "{spent} / {limit}", percentage, ›,
   progress bar; non-"on track" states are also written. Tap → expenses of that category.
6. Outline full-width "Modifier le budget" (56 dp). Scope note (household only).
   Empty period: empty state + "Définir le budget" / "Copier le budget précédent". Past period note.

## 6. Paramètres — `src/features/settings/SettingsScreen.tsx`

1. "Paramètres" (H1).
2. Household card: 56 dp avatar, household name (semibold), info line, ›. Opens Settings › Foyer
   (household details + invitations).
3. Card list (52 dp rows, dividers): Profil, Foyer, Catégories, Budget, Règles de catégorisation,
   Notifications, Sécurité, Données & confidentialité.
4. Card list: Aide & support, À propos.
5. Secondary "Se déconnecter" with confirmation (A-16).
   Rows without a destination yet show "Bientôt disponible" (A-15).

## 7. Tickets (receipt vault) — `src/features/receipt/` (DOC-002, not in the reference)

Built only from the approved components (A-19): Settings › Tickets.

1. Header: ← back, "Tickets" (H1), bordered + icon button (opens "Ajouter une dépense").
2. Segmented Tous / Photos / PDF (filter over the loaded pages).
3. Receipt cards: 40 dp receipt tile, upload date (medium), "Photos · 2 pages" (secondary), state, chevron.
   Cursor pagination, pull to refresh, empty state with "Ajouter un ticket", error state with "Réessayer".
4. Detail ("Ticket"): summary card, page preview (short-lived URL, never cached), page pager for multi-page
   receipts, "Télécharger / partager" (secondary), "Supprimer le ticket" (destructive, confirmation sheet) for
   open receipts only.

Scanner / Importer panels of section 3 are functional since DOC-002: shutter = system camera (one page per
shot, up to 5), gallery = multi-select, Importer = PDF or photos; selected pages appear as thumbnails in the
viewfinder (removable); "Envoyer le ticket" uploads, then opens the stored receipt. The flash control stays
disabled (the system camera owns the flash).
