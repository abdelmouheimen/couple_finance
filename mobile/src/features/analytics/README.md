Feature module mirroring the backend 'analytics' module (MOBILE-006, MOBILE-008): Home dashboard.

- Blocks, in order: period, Household/Personal scope, spending + budget status, category breakdown
  (donut + ranked list), spending over time (line), recent expenses.
- All figures come from the backend verbatim (BR-ANA-01, BR-MON-08): `GET /api/v1/analytics/periods/{periodStart}`
  (totals, category shares, budget status), `.../daily-cumulative` (spending-over-time series) and the expense list
  (recent expenses). Nothing is summed or rebuilt on the device.
- Charts are small hand-written `react-native-svg` components (`charts/`); `chartModel.ts` is the pure mapping. The only
  numbers derived for drawing are server percentages read as integer tenths and exact bigint ratios scaled to permille;
  a monetary amount is never converted to a JS number. Every chart exposes a text alternative.
- "Other" grouping exists in the donut only; the ranked list keeps every category (one tap: "Show all").
- The period start comes from the shared `currentBudgetPeriod` utility via `HouseholdProvider`.
- After any expense/budget change, call `queryClient.invalidateQueries({ queryKey: ANALYTICS_QUERY_KEY })`.
- Category drill-down navigates to `/expenses?categoryId=…&periodStart=…` (consumed by the expense list).
- Not consumed (not needed by the approved charts): `GET /api/v1/analytics/trend`.
