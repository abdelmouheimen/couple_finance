import { strings } from "@/shared/i18n/strings";
import type { Money } from "@/shared/money/money";
import type { Category, PeriodAnalytics } from "./analyticsApi";

export interface CategoryRow {
    categoryId: string;
    name: string;
    total: Money;
    /** Server-provided percentage string (BR-ANA-05), shown verbatim; absent for negative categories. */
    percentage?: string;
}

export function categoryName(category: Category | undefined): string {
    if (!category) return strings.dashboard.unknownCategory;
    if (category.name) return category.name;
    const code = category.systemCode as keyof typeof strings.dashboard.systemCategories | undefined;
    return (code && strings.dashboard.systemCategories[code]) || strings.dashboard.unknownCategory;
}

function toMoney(m: { amount?: string; currency?: string } | undefined): Money | undefined {
    return m?.amount !== undefined && m.currency !== undefined
        ? { amount: m.amount, currency: m.currency }
        : undefined;
}

/**
 * Maps API categories to display rows. Values are passed through verbatim (BR-ANA-01, BR-MON-08):
 * no summation, no percentage computation, no re-sorting (the API already ranks largest first); only a name
 * lookup. Every category is kept: grouping into "Other" is a visual concern of the chart only.
 */
export function toCategoryRows(
    items: PeriodAnalytics["categories"],
    categories: readonly Category[],
): CategoryRow[] {
    const byId = new Map(categories.map((c) => [c.id, c]));
    const rows: CategoryRow[] = [];
    for (const item of items) {
        const total = toMoney(item.total);
        if (!item.categoryId || !total) continue;
        rows.push({
            categoryId: item.categoryId,
            name: categoryName(byId.get(item.categoryId)),
            total,
            percentage: item.percentage,
        });
    }
    return rows;
}

/** Refund-dominated categories, as provided (BR-ANA-06): listed apart, never charted. */
export function toNegativeRows(
    items: PeriodAnalytics["negativeCategories"],
    categories: readonly Category[],
): CategoryRow[] {
    return toCategoryRows(items, categories);
}

export { toMoney };
