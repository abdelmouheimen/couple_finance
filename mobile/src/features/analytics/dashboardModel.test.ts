import type { Category } from "./analyticsApi";
import { categoryName, toCategoryRows, toNegativeRows } from "./dashboardModel";

const cat = (id: string, extra: Partial<Category> = {}): Category => ({
    id,
    archived: false,
    color: "#000000",
    icon: "tag",
    sortOrder: 1,
    type: "CUSTOM",
    version: 1,
    ...extra,
});

const eur = (amount: string) => ({ amount, currency: "EUR" });

describe("dashboard chart data mapping", () => {
    test("BR-ANA-01 / BR-MON-08: values and percentages pass through verbatim, order kept", () => {
        const rows = toCategoryRows(
            [
                { categoryId: "a", total: eur("70.10"), percentage: "70.1" },
                { categoryId: "b", total: eur("29.90"), percentage: "29.9" },
            ],
            [cat("a", { name: "Pets" }), cat("b", { systemCode: "GROCERIES", type: "SYSTEM" })],
        );
        expect(rows).toEqual([
            { categoryId: "a", name: "Pets", total: eur("70.10"), percentage: "70.1" },
            { categoryId: "b", name: "Groceries", total: eur("29.90"), percentage: "29.9" },
        ]);
    });

    test("every category is kept in the detailed rows, without recomputing anything", () => {
        const items = ["a", "b", "c", "d", "e", "f", "g"].map((id) => ({
            categoryId: id,
            total: eur("1.00"),
            percentage: "14.3",
        }));
        expect(toCategoryRows(items, [])).toHaveLength(7);
    });

    test("BR-ANA-06: negative categories keep their negative amount and carry no percentage", () => {
        const rows = toNegativeRows([{ categoryId: "a", total: eur("-12.00") }], [cat("a")]);
        expect(rows[0]?.total.amount).toBe("-12.00");
        expect(rows[0]?.percentage).toBeUndefined();
    });

    test("an unknown category id falls back to a generic label", () => {
        expect(categoryName(undefined)).toBe("Category");
    });
});
