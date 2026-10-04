import { categoryName, formFromBudget, toSetBudgetRequest, validateForm } from "./budgetForm";

describe("budget form", () => {
    test("BR-MON-01: request amounts stay strings with the household currency", () => {
        const request = toSetBudgetRequest(
            { overall: "500.00", lines: [{ categoryId: "c1", amount: "120.50" }] },
            "EUR",
        );
        expect(request).toEqual({
            overallLimit: { amount: "500.00", currency: "EUR" },
            categoryLimits: [{ categoryId: "c1", limit: { amount: "120.50", currency: "EUR" } }],
        });
        expect(typeof request.overallLimit?.amount).toBe("string");
    });

    test("BR-BUD-02: an empty overall limit is omitted and the category lines are still sent", () => {
        const request = toSetBudgetRequest(
            { overall: "", lines: [{ categoryId: "c1", amount: "10.00" }] },
            "EUR",
        );
        expect(request.overallLimit).toBeUndefined();
        expect(request.categoryLimits).toHaveLength(1);
    });

    test("BR-MON-03: too many decimals are rejected before sending", () => {
        expect(() => toSetBudgetRequest({ overall: "1.234", lines: [] }, "EUR")).toThrow();
    });

    test("BR-BUD-02: at least one limit is required", () => {
        expect(validateForm({ overall: "", lines: [] }).form).toBeDefined();
        expect(validateForm({ overall: "10.00", lines: [] }).form).toBeUndefined();
        expect(
            validateForm({ overall: "", lines: [{ categoryId: "c", amount: "1.00" }] }).form,
        ).toBeUndefined();
    });

    test("amounts must be above zero", () => {
        expect(validateForm({ overall: "0.00", lines: [] }).overall).toBeDefined();
        expect(
            validateForm({ overall: "", lines: [{ categoryId: "c", amount: "" }] }).lines.c,
        ).toBeDefined();
    });

    test("the form starts from the stored limits", () => {
        const form = formFromBudget({
            overallLimit: { amount: "100.00", currency: "EUR" },
            categoryLimits: [{ categoryId: "c", limit: { amount: "5.00", currency: "EUR" } }],
        } as never);
        expect(form).toEqual({ overall: "100.00", lines: [{ categoryId: "c", amount: "5.00" }] });
    });

    test("category names: system codes are translated, custom names kept, archived flagged", () => {
        expect(categoryName({ systemCode: "GROCERIES", archived: false } as never)).toBe(
            "Groceries",
        );
        expect(categoryName({ name: "Pets", archived: true } as never)).toContain("Pets");
        expect(categoryName(undefined)).toBe("Category");
    });
});
