/**
 * Pure expense form logic. Amounts are decimal strings end to end (BR-MON-01..07): the device never
 * parses them into a number, never sums or rounds. Client validation is UX only; the server decides.
 */
import { localDateIn } from "@/features/household/currentPeriod";
import { type Money, MoneyError, parseAmount, toMinorUnits } from "@/shared/money/money";
import { strings } from "@/shared/i18n/strings";
import type { Category, CreateExpenseRequest, Expense, UpdateExpenseRequest } from "./expenseApi";

export type Sharing = "SHARED" | "PERSONAL";

export interface ExpenseFormValues {
    amount: string;
    categoryId: string | null;
    date: string;
    merchant: string;
    note: string;
    sharingType: Sharing;
}

export type FormErrors = Partial<Record<"amount" | "categoryId" | "date", string>>;

const DATE_RE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** `YYYY-MM-DD` of today in the household time zone (BR-HH-05). */
export function todayIn(timeZone: string, now: Date = new Date()): string {
    const { y, m, d } = localDateIn(now, timeZone);
    return isoDate(y, m, d);
}

function isoDate(y: number, m: number, d: number): string {
    return `${String(y).padStart(4, "0")}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`;
}

function parseDate(value: string): { y: number; m: number; d: number } | null {
    const match = DATE_RE.exec(value);
    if (!match) return null;
    const [y, m, d] = [Number(match[1]), Number(match[2]), Number(match[3])];
    const probe = new Date(Date.UTC(y, m - 1, d));
    const valid =
        probe.getUTCFullYear() === y && probe.getUTCMonth() === m - 1 && probe.getUTCDate() === d;
    return valid ? { y, m, d } : null;
}

/** Calendar shift of an ISO date (UTC arithmetic on dates only, no time zone involved). */
export function shiftDate(value: string, days: number, years: number): string {
    const p = parseDate(value);
    if (!p) return value;
    const t = new Date(Date.UTC(p.y + years, p.m - 1, p.d + days));
    return isoDate(t.getUTCFullYear(), t.getUTCMonth() + 1, t.getUTCDate());
}

/** UX-only mirror of BR-EXP-06: at most 1 day after today, at most 5 years old. */
export function validateDate(date: string, today: string): string | undefined {
    if (!parseDate(date)) return strings.errors.dateInvalid;
    if (date > shiftDate(today, 1, 0) || date < shiftDate(today, 0, -5)) {
        return strings.errors.dateOutOfRange;
    }
    return undefined;
}

/** UX-only: strictly positive and within the currency decimals (BR-MON-03). */
export function validateAmount(amount: string, currency: string): string | undefined {
    if (amount === "") return strings.errors.amountInvalid;
    try {
        parseAmount(amount, currency);
        return toMinorUnits(amount, currency) > 0n ? undefined : strings.errors.amountNotPositive;
    } catch (e) {
        if (e instanceof MoneyError) return strings.errors.amountInvalid;
        throw e;
    }
}

export function validateForm(
    values: ExpenseFormValues,
    ctx: { currency: string; today: string; categoryRequired: boolean; dateChanged?: boolean },
): FormErrors {
    const errors: FormErrors = {};
    const amount = validateAmount(values.amount, ctx.currency);
    if (amount) errors.amount = amount;
    if (ctx.categoryRequired && !values.categoryId)
        errors.categoryId = strings.errors.categoryRequired;
    // On edit the window is checked server-side only when the date changes, so mirror that.
    if (ctx.dateChanged !== false) {
        const date = validateDate(values.date, ctx.today);
        if (date) errors.date = date;
    }
    return errors;
}

function optional(text: string): string | undefined {
    const trimmed = text.trim();
    return trimmed === "" ? undefined : trimmed;
}

/** Quick-create request: ONE item for the full amount, so there is no split math on the device. */
export function buildCreateRequest(
    values: ExpenseFormValues,
    ctx: { currency: string; paidByUserId: string },
): CreateExpenseRequest {
    if (!values.categoryId) throw new Error("categoryId is required");
    const money = { amount: values.amount, currency: ctx.currency };
    const merchant = optional(values.merchant);
    const note = optional(values.note);
    return {
        kind: "EXPENSE",
        amount: money,
        date: values.date,
        paidByUserId: ctx.paidByUserId,
        sharingType: values.sharingType,
        items: [{ amount: money, categoryId: values.categoryId }],
        ...(merchant ? { merchant } : {}),
        ...(note ? { note } : {}),
    } as CreateExpenseRequest;
}

export function isSingleItem(expense: Expense): boolean {
    return expense.items.length === 1;
}

/**
 * Update request. A multi-item expense keeps its items and total exactly as the server returned
 * them (strings, no recomputation); a single-item expense carries the edited amount/category.
 */
export function buildUpdateRequest(
    expense: Expense,
    values: ExpenseFormValues,
    currency: string,
): UpdateExpenseRequest {
    const merchant = optional(values.merchant);
    const note = optional(values.note);
    let amount = expense.amount;
    let items = expense.items.map((i) => ({
        amount: i.amount,
        categoryId: i.categoryId,
        ...(i.label ? { label: i.label } : {}),
    }));
    const [only] = expense.items;
    if (isSingleItem(expense) && only && values.categoryId) {
        amount = { amount: values.amount, currency };
        items = [
            {
                amount,
                categoryId: values.categoryId,
                ...(only.label ? { label: only.label } : {}),
            },
        ];
    }
    return {
        amount,
        date: values.date,
        paidByUserId: expense.paidByUserId,
        sharingType: values.sharingType,
        items,
        ...(merchant ? { merchant } : {}),
        ...(note ? { note } : {}),
    } as UpdateExpenseRequest;
}

export function initialValues(expense: Expense): ExpenseFormValues {
    return {
        amount: expense.amount.amount ?? "",
        categoryId: expense.items[0]?.categoryId ?? null,
        date: expense.date,
        merchant: expense.merchant ?? "",
        note: expense.note ?? "",
        sharingType: expense.sharingType,
    };
}

/** BR-EXP-09: only the payer may switch SHARED <-> PERSONAL (PERSONAL also needs the creator). */
export function canChangeSharing(expense: Expense, me: string): boolean {
    if (expense.kind === "REFUND") return false;
    return (
        expense.paidByUserId === me &&
        (expense.sharingType === "PERSONAL" || expense.createdBy === me)
    );
}

export function categoryLabel(category: Category): string {
    if (category.name) return category.name;
    if (category.systemCode) return strings.categories[category.systemCode] ?? category.systemCode;
    return strings.expense.untitled;
}

/**
 * Pickable categories (BR-CAT-03, BR-EXP-14): active ones, plus archived categories already used by
 * the expense being edited so an unchanged item stays selectable and valid.
 */
export function selectableCategories(
    all: readonly Category[],
    keepIds: readonly string[],
): Category[] {
    return all.filter((c) => !c.archived || keepIds.includes(c.id));
}

/** Random, non-secret key (1-100 printable ASCII). Falls back when `crypto.randomUUID` is absent. */
export function generateIdempotencyKey(): string {
    const c = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto;
    if (c?.randomUUID) return c.randomUUID();
    const part = () => Math.random().toString(16).slice(2, 10).padEnd(8, "0");
    return `${part()}-${part()}-${part()}-${Date.now().toString(16)}`;
}

/**
 * Idempotency-Key per submit attempt (BR-EXP-13). The same payload reuses its key, so a retry after
 * a network failure can never create a duplicate; a changed payload gets a fresh key (the server
 * rejects same key + different body with 422); `reset()` after a successful save.
 */
export class IdempotencyKeys {
    private payload: string | null = null;
    private key: string | null = null;

    constructor(private readonly generate: () => string = generateIdempotencyKey) {}

    keyFor(payload: unknown): string {
        const serialized = JSON.stringify(payload);
        if (this.key === null || this.payload !== serialized) {
            this.key = this.generate();
            this.payload = serialized;
        }
        return this.key;
    }

    reset(): void {
        this.key = null;
        this.payload = null;
    }
}

/** API money (loosely typed in the schema) as the app's Money value. */
export function toMoney(m: { amount?: string; currency?: string }): Money {
    return { amount: m.amount ?? "0", currency: m.currency ?? "" };
}
