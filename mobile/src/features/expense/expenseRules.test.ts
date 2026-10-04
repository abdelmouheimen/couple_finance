import { assert, bigInt, integer, property, tuple } from "fast-check";
import { ApiError } from "@/shared/api/problem";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { errorTarget } from "./ExpenseForm";
import type { Category, Expense } from "./expenseApi";
import {
    buildCreateRequest,
    buildUpdateRequest,
    canChangeSharing,
    IdempotencyKeys,
    selectableCategories,
    todayIn,
    validateAmount,
    validateDate,
} from "./expenseRules";

const values = {
    amount: "12.50",
    categoryId: "c1",
    date: "2026-03-15",
    merchant: "  Bakery ",
    note: "",
    sharingType: "SHARED" as const,
};

function expense(over: Partial<Expense> = {}): Expense {
    return {
        id: "e1",
        amount: { amount: "30.00", currency: "EUR" },
        createdAt: "2026-03-15T10:00:00Z",
        createdBy: "me",
        date: "2026-03-15",
        items: [
            {
                id: "i1",
                categoryId: "c1",
                amount: { amount: "10.00", currency: "EUR" },
                position: 0,
            },
            {
                id: "i2",
                categoryId: "c2",
                amount: { amount: "20.00", currency: "EUR" },
                position: 1,
            },
        ],
        kind: "EXPENSE",
        paidByUserId: "me",
        sharingType: "SHARED",
        source: "MANUAL",
        version: 3,
        ...over,
    };
}

describe("quick-add request mapping (BR-MON-01..07, BR-EXP-01)", () => {
    test("BR_MON_01_amount_is_sent_as_a_string_with_currency_never_a_number", () => {
        const req = buildCreateRequest(values, { currency: "EUR", paidByUserId: "me" });
        expect(req.amount).toEqual({ amount: "12.50", currency: "EUR" });
        expect(typeof req.amount.amount).toBe("string");
        expect(req.items).toEqual([
            { amount: { amount: "12.50", currency: "EUR" }, categoryId: "c1" },
        ]);
        expect(JSON.stringify(req)).toContain('"amount":"12.50"');
    });

    test("approved defaults: EXPENSE, payer is the authenticated user, merchant trimmed", () => {
        const req = buildCreateRequest(values, { currency: "EUR", paidByUserId: "me" });
        expect(req.kind).toBe("EXPENSE");
        expect(req.paidByUserId).toBe("me");
        expect(req.merchant).toBe("Bakery");
        expect(req).not.toHaveProperty("note");
    });

    test("BR_MON_07_property_amount_string_round_trip_never_changes_value", () => {
        const canonical = tuple(
            bigInt({ min: 1n, max: 10n ** 12n }),
            integer({ min: 0, max: 99 }),
        ).map(([whole, cents]) => `${whole}.${String(cents).padStart(2, "0")}`);
        assert(
            property(canonical, (amount) => {
                const req = buildCreateRequest(
                    { ...values, amount },
                    { currency: "EUR", paidByUserId: "me" },
                );
                const parsed = JSON.parse(JSON.stringify(req)) as typeof req;
                expect(parsed.amount.amount).toBe(amount);
                expect(parsed.items?.[0]?.amount.amount).toBe(amount);
            }),
        );
    });
});

describe("client validation is UX only", () => {
    test("BR_MON_03_amount_must_be_positive_with_valid_decimals", () => {
        expect(validateAmount("12.50", "EUR")).toBeUndefined();
        expect(validateAmount("0.00", "EUR")).toBe(strings.errors.amountNotPositive);
        expect(validateAmount("", "EUR")).toBe(strings.errors.amountInvalid);
        expect(validateAmount("1.234", "EUR")).toBe(strings.errors.amountInvalid);
        expect(validateAmount("100", "JPY")).toBeUndefined();
    });

    test("BR_EXP_06_date_window_is_one_day_ahead_and_five_years_back", () => {
        const today = "2026-03-15";
        expect(validateDate("2026-03-16", today)).toBeUndefined();
        expect(validateDate("2026-03-17", today)).toBe(strings.errors.dateOutOfRange);
        expect(validateDate("2021-03-15", today)).toBeUndefined();
        expect(validateDate("2021-03-14", today)).toBe(strings.errors.dateOutOfRange);
        expect(validateDate("2026-02-30", today)).toBe(strings.errors.dateInvalid);
    });

    test("BR_HH_05_today_is_computed_in_the_household_timezone", () => {
        const instant = new Date("2026-03-14T23:30:00Z");
        expect(todayIn("Europe/Paris", instant)).toBe("2026-03-15");
        expect(todayIn("America/New_York", instant)).toBe("2026-03-14");
    });
});

describe("idempotency key handling (BR-EXP-13)", () => {
    test("a retry with the same payload reuses the same key", () => {
        let n = 0;
        const keys = new IdempotencyKeys(() => `key-${++n}`);
        const a = keys.keyFor({ amount: "1.00" });
        expect(keys.keyFor({ amount: "1.00" })).toBe(a);
    });

    test("a changed payload gets a new key and reset starts over", () => {
        let n = 0;
        const keys = new IdempotencyKeys(() => `key-${++n}`);
        const a = keys.keyFor({ amount: "1.00" });
        expect(keys.keyFor({ amount: "2.00" })).not.toBe(a);
        const b = keys.keyFor({ amount: "2.00" });
        keys.reset();
        expect(keys.keyFor({ amount: "2.00" })).not.toBe(b);
    });
});

describe("problem-code mapping", () => {
    const problem = (code: string, status = 400) => new ApiError({ code, status, title: "" });

    test.each([
        ["EXPENSE_ITEMS_SUM_MISMATCH", strings.errors.itemsMismatch, "banner"],
        ["AMOUNT_EXCEEDS_MAXIMUM", strings.errors.amountTooLarge, "amount"],
        ["EXPENSE_CATEGORY_ARCHIVED", strings.errors.categoryArchived, "categoryId"],
        ["CATEGORY_ARCHIVED", strings.errors.categoryArchived, "banner"],
        ["EXPENSE_DATE_OUT_OF_RANGE", strings.errors.dateOutOfRange, "date"],
        ["EXPENSE_HAS_LIVE_REFUNDS", strings.errors.hasLiveRefunds, "banner"],
        ["REQUEST_IN_PROGRESS", strings.errors.requestInProgress, "banner"],
        ["VERSION_CONFLICT", strings.errors.conflict, "banner"],
    ])("%s maps to a friendly message and the right target", (code, message, target) => {
        expect(errorMessage(problem(code))).toBe(message);
        expect(errorTarget(problem(code))).toBe(target);
    });
});

describe("editing", () => {
    test("BR_EXP_08_multi_item_edit_keeps_server_items_and_total_untouched", () => {
        const e = expense();
        const req = buildUpdateRequest(e, { ...values, merchant: "New" }, "EUR");
        expect(req.amount).toEqual(e.amount);
        expect(req.items.map((i) => i.amount.amount)).toEqual(["10.00", "20.00"]);
        expect(req.merchant).toBe("New");
        expect(req.paidByUserId).toBe("me");
    });

    test("a single-item edit carries the edited amount and category", () => {
        const e = expense({
            items: [expense().items[0]!],
            amount: { amount: "10.00", currency: "EUR" },
        });
        const req = buildUpdateRequest(e, { ...values, amount: "11.00", categoryId: "c9" }, "EUR");
        expect(req.items).toEqual([
            { amount: { amount: "11.00", currency: "EUR" }, categoryId: "c9" },
        ]);
        expect(req.amount.amount).toBe("11.00");
    });

    test("BR_EXP_09_only_the_payer_may_change_sharing", () => {
        expect(canChangeSharing(expense(), "me")).toBe(true);
        expect(canChangeSharing(expense(), "partner")).toBe(false);
        expect(canChangeSharing(expense({ paidByUserId: "partner" }), "me")).toBe(false);
        expect(canChangeSharing(expense({ kind: "REFUND" }), "me")).toBe(false);
    });

    test("BR_EXP_14_archived_categories_are_not_selectable_unless_already_on_the_expense", () => {
        const cat = (id: string, archived: boolean) => ({ id, archived }) as unknown as Category;
        const all = [cat("a", false), cat("b", true), cat("c", true)];
        expect(selectableCategories(all, []).map((c) => c.id)).toEqual(["a"]);
        expect(selectableCategories(all, ["c"]).map((c) => c.id)).toEqual(["a", "c"]);
    });
});
