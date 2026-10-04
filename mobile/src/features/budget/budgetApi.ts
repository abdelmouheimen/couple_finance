import type { components } from "@/shared/api/client";
import { api } from "@/shared/api/instance";
import { ApiError } from "@/shared/api/problem";
import { unwrap } from "@/shared/api/unwrap";

export type Budget = components["schemas"]["Budget"];
export type Category = components["schemas"]["Category"];
export type SetBudgetRequest = components["schemas"]["SetBudgetRequest"];

export const budgetKey = (periodStart: string) => ["budget", periodStart] as const;
export const CATEGORIES_KEY = ["categories", "all"] as const;

/**
 * The budget of the period, or `null` when the period has none (404 BUDGET_NOT_FOUND = empty state).
 * Any other 404 (e.g. BUDGET_PERIOD_NOT_FOUND for an unexpected period start) stays an error.
 */
export async function fetchBudget(periodStart: string): Promise<Budget | null> {
    try {
        return await unwrap(
            api.GET("/api/v1/budgets/{periodStart}", { params: { path: { periodStart } } }),
        );
    } catch (e) {
        if (e instanceof ApiError && e.status === 404 && e.code === "BUDGET_NOT_FOUND") {
            return null;
        }
        throw e;
    }
}

/** If-Match carries the version last read (strong ETag form). Omitted when creating. */
export function setBudget(
    periodStart: string,
    request: SetBudgetRequest,
    version?: number,
): Promise<Budget> {
    return unwrap(
        api.PUT("/api/v1/budgets/{periodStart}", {
            params: {
                path: { periodStart },
                ...(version === undefined ? {} : { header: { "If-Match": `"${version}"` } }),
            },
            body: request,
        }),
    );
}

export function copyPreviousBudget(periodStart: string): Promise<Budget> {
    return unwrap(
        api.POST("/api/v1/budgets/{periodStart}/copy-previous", {
            params: { path: { periodStart } },
        }),
    );
}

/** All categories (archived included, so archived limit lines keep their name), following the cursor. */
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
