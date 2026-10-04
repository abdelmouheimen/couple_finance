import { strings } from "@/shared/i18n/strings";
import { parseAmount } from "@/shared/money/money";
import type { Budget, Category, SetBudgetRequest } from "./budgetApi";

export interface LimitLine {
    categoryId: string;
    /** Canonical decimal string or "" while being typed. */
    amount: string;
}

export interface BudgetForm {
    overall: string;
    lines: LimitLine[];
}

export interface FormErrors {
    overall?: string;
    lines: Record<string, string>;
    form?: string;
}

export function formFromBudget(budget: Budget | null): BudgetForm {
    return {
        overall: budget?.overallLimit?.amount ?? "",
        lines: (budget?.categoryLimits ?? []).flatMap((l) =>
            l.limit.amount === undefined
                ? []
                : [{ categoryId: l.categoryId, amount: l.limit.amount }],
        ),
    };
}

/** A canonical amount above zero (string test only; no arithmetic on money). */
function isPositive(amount: string): boolean {
    return /[1-9]/.test(amount);
}

/** BR-BUD-02: at least one limit; every entered amount strictly positive. Server stays authoritative. */
export function validateForm(form: BudgetForm): FormErrors {
    const errors: FormErrors = { lines: {} };
    const hasOverall = form.overall !== "";
    if (hasOverall && !isPositive(form.overall)) errors.overall = strings.budget.amountRequired;
    for (const line of form.lines) {
        if (!isPositive(line.amount)) errors.lines[line.categoryId] = strings.budget.amountRequired;
    }
    if (!hasOverall && form.lines.length === 0) errors.form = strings.budget.limitRequired;
    return errors;
}

export function hasErrors(e: FormErrors): boolean {
    return !!e.overall || !!e.form || Object.keys(e.lines).length > 0;
}

/**
 * Request body: amounts stay decimal strings in the household currency (BR-MON-01); the complete
 * set of category lines replaces the existing ones. An empty overall limit is omitted (cleared).
 */
export function toSetBudgetRequest(form: BudgetForm, currency: string): SetBudgetRequest {
    return {
        ...(form.overall === ""
            ? {}
            : { overallLimit: { amount: parseAmount(form.overall, currency), currency } }),
        categoryLimits: form.lines.map((l) => ({
            categoryId: l.categoryId,
            limit: { amount: parseAmount(l.amount, currency), currency },
        })),
    };
}

export function categoryName(category: Category | undefined): string {
    if (!category) return strings.budget.categoryFallback;
    const base =
        category.name ??
        (category.systemCode ? strings.categoryNames[category.systemCode] : undefined) ??
        category.systemCode ??
        strings.budget.categoryFallback;
    return category.archived ? `${base} ${strings.budget.archivedSuffix}` : base;
}
