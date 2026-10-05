import type { components } from "@/shared/api/client";
import { api } from "@/shared/api/instance";
import { unwrap } from "@/shared/api/unwrap";

export type PeriodAnalytics = components["schemas"]["PeriodAnalytics"];
export type AnalyticsScope = PeriodAnalytics["scope"];
export type Category = components["schemas"]["Category"];

/** Root of every analytics cache entry: invalidate it after any change to expenses/budgets. */
export const ANALYTICS_QUERY_KEY = ["analytics"] as const;
export const CATEGORIES_QUERY_KEY = ["categories", "all"] as const;

export const periodAnalyticsKey = (periodStart: string, scope: AnalyticsScope) =>
    [...ANALYTICS_QUERY_KEY, "period", periodStart, scope] as const;

/** Server-computed analytics of the period starting on `periodStart` (BR-ANA-01: no device math). */
export function fetchPeriodAnalytics(
    periodStart: string,
    scope: AnalyticsScope,
): Promise<PeriodAnalytics> {
    return unwrap(
        api.GET("/api/v1/analytics/periods/{periodStart}", {
            params: { path: { periodStart }, query: { scope } },
        }),
    );
}

export type DailyCumulativeSeries = components["schemas"]["DailyCumulativeSeries"];

export const dailyCumulativeKey = (periodStart: string, scope: AnalyticsScope) =>
    [...ANALYTICS_QUERY_KEY, "daily-cumulative", periodStart, scope] as const;

/** Server series of the cumulative net spending, one point per day (BR-ANA-01: no device math). */
export function fetchDailyCumulative(
    periodStart: string,
    scope: AnalyticsScope,
): Promise<DailyCumulativeSeries> {
    return unwrap(
        api.GET("/api/v1/analytics/periods/{periodStart}/daily-cumulative", {
            params: { path: { periodStart }, query: { scope } },
        }),
    );
}

/** All categories (archived included: past spending may reference them), following the cursor. */
export async function fetchAllCategories(): Promise<Category[]> {
    const all: Category[] = [];
    let cursor: string | undefined;
    do {
        const page = await unwrap(
            api.GET("/api/v1/categories", {
                params: { query: { includeArchived: true, limit: 100, cursor } },
            }),
        );
        all.push(...page.items);
        cursor = page.nextCursor;
    } while (cursor);
    return all;
}
