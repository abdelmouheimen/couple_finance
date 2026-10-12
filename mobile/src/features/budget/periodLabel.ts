import type { BudgetPeriod } from "@/features/household/currentPeriod";
import { strings } from "@/shared/i18n/strings";

function utc(date: string): Date {
    return new Date(`${date}T00:00:00Z`);
}

function format(date: Date): string {
    return date.toLocaleDateString(undefined, { dateStyle: "medium", timeZone: "UTC" });
}

/** "start – last day": the stored end is exclusive, so the last day shown is the day before it. */
export function periodLabel(period: BudgetPeriod): string {
    const last = utc(period.end);
    last.setUTCDate(last.getUTCDate() - 1);
    return strings.budget.period(format(utc(period.start)), format(last));
}

function capitalize(text: string): string {
    return text.charAt(0).toUpperCase() + text.slice(1);
}

/** A period starting on the 1st is a calendar month and is titled by its month (design: "Octobre 2026"). */
function isCalendarMonth(period: BudgetPeriod): boolean {
    return period.start.endsWith("-01");
}

/**
 * Title of a period: the month and year for calendar-month periods, otherwise the exact date range
 * (households whose period starts on another day, BR-HH-05, must not be shown a misleading month).
 */
export function periodTitle(period: BudgetPeriod): string {
    if (!isCalendarMonth(period)) return periodLabel(period);
    return capitalize(
        utc(period.start).toLocaleDateString(undefined, {
            month: "long",
            year: "numeric",
            timeZone: "UTC",
        }),
    );
}

/** Short name of a period for comparisons ("vs sept."); the date range when it is not a calendar month. */
export function periodShortName(period: BudgetPeriod): string {
    if (!isCalendarMonth(period)) return periodLabel(period);
    return utc(period.start).toLocaleDateString(undefined, { month: "short", timeZone: "UTC" });
}
