/** Externalised user-facing strings (no catalogs/locales yet, MOBILE-002). */
export const strings = {
    appName: "CoupleFinance",
    loading: "Loading",
    retry: "Retry",
    cancel: "Cancel",
    close: "Close",
    tabs: { home: "Home", expenses: "Expenses", budget: "Budget" },
    addExpense: "Add expense",
    scope: { HOUSEHOLD: "Household", PERSONAL: "Personal" },
    budgetStatus: { ON_TRACK: "On track", WARNING: "Warning", EXCEEDED: "Exceeded" },
    errorDefault: "Something went wrong. Please try again.",
    placeholders: {
        home: "Your household overview will appear here.",
        expenses: "Your expenses will appear here.",
        budget: "Your budgets will appear here.",
        addExpense: "Adding an expense is coming soon.",
        auth: "Sign in is coming soon.",
        onboarding: "Onboarding is coming soon.",
    },
} as const;
