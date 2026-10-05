import { array, assert, integer, property } from "fast-check";
import {
    donutSummary,
    MAX_DONUT_SLICES,
    percentToTenths,
    tenthsToPercent,
    toDonutSlices,
    toSeriesModel,
} from "./chartModel";
import type { CategoryRow } from "./dashboardModel";

const eur = (amount: string) => ({ amount, currency: "EUR" });
const row = (id: string, percentage: string | undefined, total = "1.00"): CategoryRow => ({
    categoryId: id,
    name: `Cat ${id}`,
    total: eur(total),
    ...(percentage === undefined ? {} : { percentage }),
});

describe("donut slices (BR-ANA-05, BR-ANA-01)", () => {
    test("percentages are read as integer tenths without floating point", () => {
        expect(percentToTenths("72.9")).toBe(729);
        expect(percentToTenths("100.0")).toBe(1000);
        expect(percentToTenths("5")).toBe(50);
        expect(percentToTenths(undefined)).toBe(0);
        expect(percentToTenths("abc")).toBe(0);
        expect(tenthsToPercent(729)).toBe("72.9");
        expect(tenthsToPercent(1000)).toBe("100.0");
    });

    test("one category: a single slice with the server percentage verbatim", () => {
        const slices = toDonutSlices([row("a", "100.0", "50.00")]);
        expect(slices).toEqual([
            { key: "a", label: "Cat a", percentage: "100.0", tenths: 1000, other: false },
        ]);
    });

    test("up to the slice limit every category is its own slice, order kept", () => {
        const rows = ["a", "b", "c", "d", "e"].map((id) => row(id, "20.0"));
        const slices = toDonutSlices(rows);
        expect(slices.map((s) => s.key)).toEqual(["a", "b", "c", "d", "e"]);
        expect(slices.some((s) => s.other)).toBe(false);
    });

    test("more categories: the remainder is grouped as Other, its percentage the exact server sum", () => {
        const rows = [
            row("a", "40.0"),
            row("b", "20.0"),
            row("c", "15.0"),
            row("d", "10.0"),
            row("e", "5.1"),
            row("f", "4.9"),
            row("g", "3.2"),
            row("h", "1.8"),
        ];
        const slices = toDonutSlices(rows);
        expect(slices).toHaveLength(MAX_DONUT_SLICES + 1);
        const other = slices[MAX_DONUT_SLICES]!;
        expect(other.other).toBe(true);
        expect(other.label).toBe("Other");
        expect(other.percentage).toBe("9.9"); // 4.9 + 3.2 + 1.8, summed in tenths
        expect(slices.slice(0, MAX_DONUT_SLICES).map((s) => s.percentage)).toEqual([
            "40.0",
            "20.0",
            "15.0",
            "10.0",
            "5.1",
        ]);
    });

    test("zero spending / refund-only: nothing to draw", () => {
        expect(toDonutSlices([])).toEqual([]);
        expect(toDonutSlices([row("a", undefined, "-5.00")])).toEqual([]);
    });

    test("text alternative lists the same names and server percentages", () => {
        const slices = toDonutSlices([row("a", "72.9"), row("b", "27.1")]);
        expect(donutSummary(slices, "Household")).toBe(
            "Spending by category, Household: Cat a 72.9 %, Cat b 27.1 %",
        );
    });

    test("property: Other keeps the server percentages summing to the same total", () => {
        assert(
            property(
                array(integer({ min: 1, max: 500 }), { minLength: 1, maxLength: 20 }),
                (tenths) => {
                    const rows = tenths.map((t, i) => row(String(i), tenthsToPercent(t)));
                    const slices = toDonutSlices(rows);
                    const before = tenths.reduce((a, b) => a + b, 0);
                    const after = slices.reduce((a, s) => a + s.tenths, 0);
                    expect(after).toBe(before);
                },
            ),
        );
    });
});

describe("spending over time model (BR-ANA-01, BR-MON-08)", () => {
    const base = { periodStart: "2026-03-01", periodEnd: "2026-04-01" };

    test("empty series -> no model", () => {
        expect(toSeriesModel({ ...base, points: [] })).toBeUndefined();
    });

    test("axis starts at zero and the budget line is placed by exact ratio", () => {
        const model = toSeriesModel({
            ...base,
            limit: eur("200.00"),
            points: [
                { date: "2026-03-01", cumulative: eur("50.00") },
                { date: "2026-03-02", cumulative: eur("100.00") },
            ],
        })!;
        expect(model.bottom.amount).toBe("0.00");
        expect(model.top.amount).toBe("200.00");
        expect(model.zeroYPermille).toBe(0);
        expect(model.limit).toEqual({ money: eur("200.00"), yPermille: 1000 });
        expect(model.points.map((p) => p.yPermille)).toEqual([250, 500]);
        expect(model.points[0]!.xPermille).toBe(0);
        expect(model.points[1]!.xPermille).toBe(Math.round(1000 / 30));
    });

    test("days without spending keep the server's repeated cumulative (flat segment)", () => {
        const model = toSeriesModel({
            ...base,
            points: [
                { date: "2026-03-01", cumulative: eur("10.00") },
                { date: "2026-03-02", cumulative: eur("10.00") },
                { date: "2026-03-03", cumulative: eur("30.00") },
            ],
        })!;
        expect(model.points.map((p) => p.yPermille)).toEqual([333, 333, 1000]);
        expect(model.top.amount).toBe("30.00");
    });

    test("a single point is positioned and has first == last", () => {
        const model = toSeriesModel({
            ...base,
            points: [{ date: "2026-03-01", cumulative: eur("12.50") }],
        })!;
        expect(model.points).toHaveLength(1);
        expect(model.first).toBe(model.last);
        expect(model.last!.yPermille).toBe(1000);
    });

    test("a flat zero series sits on the baseline with exact labels", () => {
        const model = toSeriesModel({
            ...base,
            points: [
                { date: "2026-03-01", cumulative: eur("0.00") },
                { date: "2026-03-02", cumulative: eur("0.00") },
            ],
        })!;
        expect(model.points.map((p) => p.yPermille)).toEqual([0, 0]);
        expect(model.top.amount).toBe("0.00");
    });

    test("refunds can make the cumulative negative: the axis keeps zero inside", () => {
        const model = toSeriesModel({
            ...base,
            points: [
                { date: "2026-03-01", cumulative: eur("-20.00") },
                { date: "2026-03-02", cumulative: eur("20.00") },
            ],
        })!;
        expect(model.bottom.amount).toBe("-20.00");
        expect(model.top.amount).toBe("20.00");
        expect(model.zeroYPermille).toBe(500);
    });

    test("BR-MON-02: very large amounts never go through a JS number", () => {
        const huge = "90071992547409930.01"; // > Number.MAX_SAFE_INTEGER in minor units
        const model = toSeriesModel({
            ...base,
            limit: eur(huge),
            points: [
                { date: "2026-03-01", cumulative: eur("45035996273704965.00") },
                { date: "2026-03-02", cumulative: eur(huge) },
            ],
        })!;
        expect(model.top.amount).toBe(huge);
        expect(model.points[1]!.yPermille).toBe(1000);
        expect(model.points[0]!.yPermille).toBe(499); // just under one half, exact bigint ratio floored
        expect(model.limit!.money.amount).toBe(huge);
    });

    test("a limit in another currency is ignored", () => {
        const model = toSeriesModel({
            ...base,
            limit: { amount: "100.00", currency: "USD" },
            points: [{ date: "2026-03-01", cumulative: eur("10.00") }],
        })!;
        expect(model.limit).toBeUndefined();
    });

    test("property: permille positions stay within 0..1000 and are monotone for a cumulative series", () => {
        assert(
            property(
                array(integer({ min: 0, max: 1_000_000 }), { minLength: 1, maxLength: 28 }),
                (increments) => {
                    let sum = 0;
                    const points = increments.map((inc, i) => {
                        sum += inc;
                        const minor = String(sum).padStart(3, "0");
                        return {
                            date: `2026-03-${String(i + 1).padStart(2, "0")}`,
                            cumulative: eur(`${minor.slice(0, -2)}.${minor.slice(-2)}`),
                        };
                    });
                    const model = toSeriesModel({ ...base, points })!;
                    const ys = model.points.map((p) => p.yPermille);
                    for (const y of ys) {
                        expect(y).toBeGreaterThanOrEqual(0);
                        expect(y).toBeLessThanOrEqual(1000);
                    }
                    expect([...ys].sort((a, b) => a - b)).toEqual(ys);
                },
            ),
        );
    });
});
