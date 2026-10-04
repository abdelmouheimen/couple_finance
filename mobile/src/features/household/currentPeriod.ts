/** A budget period `[start, end)`: `end` is the NEXT period's start (exclusive). Dates are `YYYY-MM-DD`. */
export interface BudgetPeriod {
    readonly start: string;
    readonly end: string;
}

function pad(n: number, width = 2): string {
    return String(n).padStart(width, "0");
}

function iso(year: number, month: number, day: number): string {
    return `${pad(year, 4)}-${pad(month)}-${pad(day)}`;
}

/** Local calendar date of `instant` in the IANA `timeZone` (never the device zone). */
export function localDateIn(instant: Date, timeZone: string): { y: number; m: number; d: number } {
    const parts = new Intl.DateTimeFormat("en-US", {
        timeZone,
        year: "numeric",
        month: "numeric",
        day: "numeric",
    }).formatToParts(instant);
    const pick = (type: string) => Number(parts.find((p) => p.type === type)?.value);
    return { y: pick("year"), m: pick("month"), d: pick("day") };
}

/**
 * The household's current budget period (BR-HH-05: `[start, next start)`, "today" in the household
 * timezone). The single period computation of the app. `periodStartDay` is 1-28, so every month
 * has that day. Throws on an invalid day or time zone rather than guessing.
 */
export function currentBudgetPeriod(
    periodStartDay: number,
    timeZone: string,
    now: Date = new Date(),
): BudgetPeriod {
    if (!Number.isInteger(periodStartDay) || periodStartDay < 1 || periodStartDay > 28) {
        throw new RangeError("periodStartDay must be an integer between 1 and 28");
    }
    const { y, m, d } = localDateIn(now, timeZone);
    // Month index arithmetic on (year, month) only; no Date objects, so DST cannot interfere.
    const startIndex = d >= periodStartDay ? y * 12 + (m - 1) : y * 12 + (m - 1) - 1;
    const endIndex = startIndex + 1;
    return {
        start: iso(Math.floor(startIndex / 12), (startIndex % 12) + 1, periodStartDay),
        end: iso(Math.floor(endIndex / 12), (endIndex % 12) + 1, periodStartDay),
    };
}
