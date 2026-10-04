import { currentBudgetPeriod, shiftBudgetPeriod } from "./currentPeriod";

const at = (iso: string) => new Date(iso);

describe("currentBudgetPeriod (BR-HH-05)", () => {
    test.each([
        ["day 1, mid month", 1, "Europe/Paris", "2026-03-15T10:00:00Z", "2026-03-01", "2026-04-01"],
        ["day 1, first day", 1, "Europe/Paris", "2026-03-01T12:00:00Z", "2026-03-01", "2026-04-01"],
        [
            "day 28 before the start",
            28,
            "Europe/Paris",
            "2026-03-10T10:00:00Z",
            "2026-02-28",
            "2026-03-28",
        ],
        [
            "day 28 on the start",
            28,
            "Europe/Paris",
            "2026-03-28T10:00:00Z",
            "2026-03-28",
            "2026-04-28",
        ],
        ["January", 15, "Europe/Paris", "2026-01-10T10:00:00Z", "2025-12-15", "2026-01-15"],
        ["December", 15, "Europe/Paris", "2026-12-20T10:00:00Z", "2026-12-15", "2027-01-15"],
        ["short month", 28, "Europe/Paris", "2026-02-27T10:00:00Z", "2026-01-28", "2026-02-28"],
    ])("%s", (_name, day, tz, now, start, end) => {
        expect(currentBudgetPeriod(day, tz, at(now))).toEqual({ start, end });
    });

    test("uses the household timezone, not the device one (date rollover)", () => {
        const now = at("2026-03-31T23:30:00Z");
        expect(currentBudgetPeriod(1, "Europe/Paris", now).start).toBe("2026-04-01");
        expect(currentBudgetPeriod(1, "America/New_York", now).start).toBe("2026-03-01");
    });

    test("DST transition days do not shift the period", () => {
        // Europe/Paris DST starts 2026-03-29 and ends 2026-10-25.
        expect(currentBudgetPeriod(1, "Europe/Paris", at("2026-03-29T00:30:00Z"))).toEqual({
            start: "2026-03-01",
            end: "2026-04-01",
        });
        expect(currentBudgetPeriod(28, "Europe/Paris", at("2026-10-25T01:30:00Z")).start).toBe(
            "2026-09-28",
        );
    });

    test("rejects an invalid periodStartDay or time zone", () => {
        expect(() => currentBudgetPeriod(0, "Europe/Paris")).toThrow(RangeError);
        expect(() => currentBudgetPeriod(29, "Europe/Paris")).toThrow(RangeError);
        expect(() => currentBudgetPeriod(1, "Not/AZone")).toThrow();
    });
});

describe("shiftBudgetPeriod", () => {
    test("moves by whole periods across year boundaries, keeping the start day", () => {
        const base = { start: "2026-01-15", end: "2026-02-15" };
        expect(shiftBudgetPeriod(base, -1)).toEqual({ start: "2025-12-15", end: "2026-01-15" });
        expect(shiftBudgetPeriod(base, 1)).toEqual({ start: "2026-02-15", end: "2026-03-15" });
        expect(shiftBudgetPeriod(base, 0)).toEqual(base);
    });
});
