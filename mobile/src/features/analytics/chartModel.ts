/**
 * Pure chart data mapping (MOBILE-008). Every figure shown comes from the API (BR-ANA-01, BR-MON-08):
 * this module only reshapes server values for drawing.
 *
 * Money safety: monetary amounts are never converted to a JS number. Chart geometry needs a position,
 * so the only numbers produced are (a) server percentage strings read as integer tenths and (b) exact
 * bigint ratios scaled to permille (0..1000) with integer division; the permille result is a small
 * integer, not an amount.
 */
import { strings } from "@/shared/i18n/strings";
import { fromMinorUnits, type Money, toMinorUnits } from "@/shared/money/money";
import type { CategoryRow } from "./dashboardModel";

/** Series colours available for slices; a longer ranking is grouped into "Other" (visual only). */
export const MAX_DONUT_SLICES = 5;
/** Rows of the ranked list shown before "show all". */
export const COLLAPSED_LIST_ROWS = MAX_DONUT_SLICES;

export interface DonutSlice {
    key: string;
    label: string;
    /** Server percentage string, verbatim; for "Other" the exact sum of the server percentages. */
    percentage: string;
    /** Same value as integer tenths of a percent (geometry only). */
    tenths: number;
    other: boolean;
}

/** "72.9" -> 729 integer tenths without floating point; invalid input -> 0. */
export function percentToTenths(percentage: string | undefined): number {
    const match = /^(\d+)(?:\.(\d))?\d*$/.exec(percentage ?? "");
    if (!match) return 0;
    return Number.parseInt(match[1]!, 10) * 10 + Number.parseInt(match[2] ?? "0", 10);
}

export function tenthsToPercent(tenths: number): string {
    return `${Math.floor(tenths / 10)}.${tenths % 10}`;
}

/**
 * Donut slices of the ranked categories (largest first, as the API sorts them). With more than
 * {@link MAX_DONUT_SLICES} categories the first ones are kept and the rest become one "Other" slice whose
 * percentage is the sum of the server percentages (integer tenths). Categories without a server
 * percentage cannot be drawn and are left out of the donut only; the list still shows them.
 */
export function toDonutSlices(rows: readonly CategoryRow[]): DonutSlice[] {
    const drawable = rows.filter((r) => percentToTenths(r.percentage) > 0);
    const toSlice = (r: CategoryRow): DonutSlice => ({
        key: r.categoryId,
        label: r.name,
        percentage: r.percentage ?? "0.0",
        tenths: percentToTenths(r.percentage),
        other: false,
    });
    if (drawable.length <= MAX_DONUT_SLICES) return drawable.map(toSlice);
    const kept = drawable.slice(0, MAX_DONUT_SLICES).map(toSlice);
    const rest = drawable.slice(MAX_DONUT_SLICES);
    const tenths = rest.reduce((sum, r) => sum + percentToTenths(r.percentage), 0);
    return [
        ...kept,
        {
            key: "other",
            label: strings.dashboard.otherCategories,
            percentage: tenthsToPercent(tenths),
            tenths,
            other: true,
        },
    ];
}

/** Text alternative of the donut: the same names and server percentages as the ranked list. */
export function donutSummary(slices: readonly DonutSlice[], scopeLabel: string): string {
    return strings.dashboard.donutA11y(
        scopeLabel,
        slices.map((s) => `${s.label} ${s.percentage} %`).join(", "),
    );
}

export interface SeriesPoint {
    date: string;
    money: Money;
    /** Horizontal position, 0..1000 across the whole period. */
    xPermille: number;
    /** Vertical position, 0 (axis bottom) .. 1000 (axis top). */
    yPermille: number;
}

export interface SeriesModel {
    points: SeriesPoint[];
    /** Axis labels, exact decimal strings from the series. */
    top: Money;
    bottom: Money;
    /** Budget line position and value, when the API gave an overall limit. */
    limit?: { money: Money; yPermille: number };
    /** Where the zero baseline sits (equals 0 unless refunds make the cumulative negative). */
    zeroYPermille: number;
    first?: SeriesPoint;
    last?: SeriesPoint;
}

interface ApiPoint {
    date?: string;
    cumulative?: { amount?: string; currency?: string };
}

const DAY_MS = 86_400_000;

function dayNumber(iso: string): number | undefined {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
    if (!m) return undefined;
    return Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])) / DAY_MS;
}

/** Exact bigint ratio `(value - lo) / (hi - lo)` in permille (small integer), `hi > lo`. */
function permille(value: bigint, lo: bigint, hi: bigint): number {
    return Number(((value - lo) * 1000n) / (hi - lo));
}

/**
 * Maps the daily cumulative series to chart positions. The axis always includes zero (no truncated,
 * misleading baseline) and the budget limit when present. Days without spending are already in the
 * series (repeated cumulative); future days are absent and simply leave the line short of the right edge.
 */
export function toSeriesModel(
    series: {
        periodStart: string;
        periodEnd: string;
        points: readonly ApiPoint[];
        limit?: { amount?: string; currency?: string };
    },
    currencyFallback = "EUR",
): SeriesModel | undefined {
    const startDay = dayNumber(series.periodStart);
    const endDay = dayNumber(series.periodEnd);
    const valid: { date: string; day: number; money: Money; minor: bigint }[] = [];
    for (const p of series.points) {
        const day = p.date ? dayNumber(p.date) : undefined;
        const c = p.cumulative;
        if (p.date === undefined || day === undefined || c?.amount === undefined) continue;
        const money = { amount: c.amount, currency: c.currency ?? currencyFallback };
        valid.push({ date: p.date, day, money, minor: toMinorUnits(money.amount, money.currency) });
    }
    if (valid.length === 0 || startDay === undefined || endDay === undefined) return undefined;

    const currency = valid[0]!.money.currency;
    const limitMoney =
        series.limit?.amount !== undefined && series.limit.currency === currency
            ? { amount: series.limit.amount, currency }
            : undefined;
    const limitMinor = limitMoney ? toMinorUnits(limitMoney.amount, currency) : undefined;

    let lo = 0n;
    let hi = 0n;
    for (const p of valid) {
        if (p.minor < lo) lo = p.minor;
        if (p.minor > hi) hi = p.minor;
    }
    if (limitMinor !== undefined && limitMinor > hi) hi = limitMinor;
    const labelHi = hi;
    if (hi === lo) hi = lo + 1n; // flat zero series: draw it on the baseline (labels stay exact)

    const spanDays = Math.max(endDay - startDay, 1);
    const points = valid.map((p): SeriesPoint => {
        const index = Math.min(Math.max(p.day - startDay, 0), spanDays - 1);
        return {
            date: p.date,
            money: p.money,
            xPermille: spanDays === 1 ? 500 : Math.round((index * 1000) / (spanDays - 1)),
            yPermille: permille(p.minor, lo, hi),
        };
    });
    return {
        points,
        top: { amount: fromMinorUnits(labelHi, currency), currency },
        bottom: { amount: fromMinorUnits(lo, currency), currency },
        ...(limitMoney && limitMinor !== undefined
            ? { limit: { money: limitMoney, yPermille: permille(limitMinor, lo, hi) } }
            : {}),
        zeroYPermille: permille(0n, lo, hi),
        first: points[0]!,
        last: points[points.length - 1]!,
    };
}
