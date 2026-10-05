import { useInfiniteQuery, useQuery, useQueryClient } from "@tanstack/react-query";
import {
    type ExpenseFilters,
    fetchCurrentUserId,
    getExpense,
    getExpenseAudit,
    listAllCategories,
    listExpenses,
} from "./expenseApi";

export const EXPENSES_KEY = ["expenses"] as const;
export const CATEGORIES_KEY = ["categories"] as const;

export function useExpensePages(filters: ExpenseFilters) {
    return useInfiniteQuery({
        queryKey: [...EXPENSES_KEY, "list", filters],
        queryFn: ({ pageParam }) => listExpenses(filters, pageParam),
        initialPageParam: undefined as string | undefined,
        getNextPageParam: (last) => last.nextCursor ?? undefined,
    });
}

/** First page of a filter, for compact previews (Home "recent expenses"); cached under EXPENSES_KEY. */
export function useRecentExpenses(filters: ExpenseFilters) {
    return useQuery({
        queryKey: [...EXPENSES_KEY, "recent", filters],
        queryFn: () => listExpenses(filters),
        retry: false,
    });
}

export function useExpense(id: string) {
    return useQuery({
        queryKey: [...EXPENSES_KEY, "detail", id],
        queryFn: () => getExpense(id),
        retry: false,
    });
}

export function useExpenseAudit(id: string, enabled: boolean) {
    return useQuery({
        queryKey: [...EXPENSES_KEY, "audit", id],
        queryFn: () => getExpenseAudit(id),
        enabled,
        retry: false,
    });
}

export function useCategories(includeArchived: boolean) {
    return useQuery({
        queryKey: [...CATEGORIES_KEY, includeArchived],
        queryFn: () => listAllCategories(includeArchived),
    });
}

/** The caller's id from the authenticated principal (never typed by the user). */
export function useCurrentUserId() {
    return useQuery({
        queryKey: ["me", "id"],
        queryFn: fetchCurrentUserId,
        staleTime: Infinity,
    });
}

export function useInvalidateExpenses() {
    const client = useQueryClient();
    return () => client.invalidateQueries({ queryKey: EXPENSES_KEY });
}

export function useInvalidateCategories() {
    const client = useQueryClient();
    return () => client.invalidateQueries({ queryKey: CATEGORIES_KEY });
}
