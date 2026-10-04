import type { components } from "@/shared/api/client";
import { api } from "@/shared/api/instance";
import { unwrap } from "@/shared/api/unwrap";

export type Expense = components["schemas"]["Expense"];
export type ExpensePage = components["schemas"]["ExpensePage"];
export type ExpenseAuditEntry = components["schemas"]["ExpenseAuditEntry"];
export type Category = components["schemas"]["Category"];
export type CategorySuggestion = components["schemas"]["CategorySuggestion"];
export type CreateExpenseRequest = components["schemas"]["CreateExpenseRequest"];
export type UpdateExpenseRequest = components["schemas"]["UpdateExpenseRequest"];
export type ExpenseScope = "HOUSEHOLD" | "PERSONAL";

export interface ExpenseFilters {
    scope: ExpenseScope;
    dateFrom?: string | undefined;
    dateTo?: string | undefined;
    categoryId?: string | undefined;
    q?: string | undefined;
}

export const PAGE_SIZE = 20;

/** ETag of a version, as the backend renders it (quoted strong tag); sent back in If-Match (BR-EXP-12). */
export function etagOf(version: number): string {
    return `"${version}"`;
}

export function listExpenses(filters: ExpenseFilters, cursor?: string): Promise<ExpensePage> {
    return unwrap(
        api.GET("/api/v1/expenses", {
            params: {
                query: {
                    scope: filters.scope,
                    limit: PAGE_SIZE,
                    ...(filters.dateFrom ? { dateFrom: filters.dateFrom } : {}),
                    ...(filters.dateTo ? { dateTo: filters.dateTo } : {}),
                    ...(filters.categoryId ? { categoryId: filters.categoryId } : {}),
                    ...(filters.q ? { q: filters.q } : {}),
                    ...(cursor ? { cursor } : {}),
                },
            },
        }),
    );
}

export function createExpense(
    body: CreateExpenseRequest,
    idempotencyKey: string,
): Promise<Expense> {
    return unwrap(
        api.POST("/api/v1/expenses", {
            body,
            params: { header: { "Idempotency-Key": idempotencyKey } },
        }),
    );
}

export function getExpense(id: string): Promise<Expense> {
    return unwrap(api.GET("/api/v1/expenses/{id}", { params: { path: { id } } }));
}

export function updateExpense(
    id: string,
    version: number,
    body: UpdateExpenseRequest,
): Promise<Expense> {
    return unwrap(
        api.PUT("/api/v1/expenses/{id}", {
            body,
            params: { path: { id }, header: { "If-Match": etagOf(version) } },
        }),
    );
}

export async function deleteExpense(id: string): Promise<void> {
    await unwrap(api.DELETE("/api/v1/expenses/{id}", { params: { path: { id } } }));
}

export function restoreExpense(id: string): Promise<Expense> {
    return unwrap(api.POST("/api/v1/expenses/{id}/restore", { params: { path: { id } } }));
}

export async function getExpenseAudit(id: string): Promise<ExpenseAuditEntry[]> {
    const trail = await unwrap(
        api.GET("/api/v1/expenses/{id}/audit", { params: { path: { id } } }),
    );
    return trail.items;
}

/** All categories (system + custom). The API is paginated: pages are followed to the end. */
export async function listAllCategories(includeArchived: boolean): Promise<Category[]> {
    const all: Category[] = [];
    let cursor: string | undefined;
    do {
        const page = await unwrap(
            api.GET("/api/v1/categories", {
                params: {
                    query: { includeArchived, limit: 100, ...(cursor ? { cursor } : {}) },
                },
            }),
        );
        all.push(...page.items);
        cursor = page.nextCursor;
    } while (cursor);
    return all;
}

export function createCategory(name: string): Promise<Category> {
    return unwrap(api.POST("/api/v1/categories", { body: { name } }));
}

export function updateCategory(
    id: string,
    version: number,
    change: { name?: string; archived?: boolean },
): Promise<Category> {
    return unwrap(
        api.PATCH("/api/v1/categories/{id}", {
            body: change,
            params: { path: { id }, header: { "If-Match": etagOf(version) } },
        }),
    );
}

export function suggestCategory(
    merchant: string,
    sharingType: "SHARED" | "PERSONAL",
): Promise<CategorySuggestion> {
    return unwrap(
        api.GET("/api/v1/category-suggestions", {
            params: { query: { merchant, sharingType } },
        }),
    );
}

export async function fetchCurrentUserId(): Promise<string> {
    const me = await unwrap(api.GET("/api/v1/me"));
    return me.id ?? "";
}
