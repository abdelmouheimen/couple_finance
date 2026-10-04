Feature module mirroring the backend 'analytics' module (MOBILE-006): Home dashboard.

- All figures come from `GET /api/v1/analytics/periods/{periodStart}` verbatim (BR-ANA-01, BR-MON-08).
- The period start comes from the shared `currentBudgetPeriod` utility via `HouseholdProvider`.
- After any expense/budget change, call `queryClient.invalidateQueries({ queryKey: ANALYTICS_QUERY_KEY })`.
- Category drill-down navigates to `/expenses?categoryId=…&periodStart=…` (consumed by the expense list).
- Dependency: the expense/budget mutations (Add-expense / Budget Issues, not yet on the base) must perform the invalidation above; it cannot be wired before they exist. Meanwhile pull-to-refresh and remount refetch.
- Product decision pending: the budget block is hidden in an empty period (spent card + empty state only).
