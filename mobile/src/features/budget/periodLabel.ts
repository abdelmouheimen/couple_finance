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
