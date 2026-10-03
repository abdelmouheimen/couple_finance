/**
 * Minimal money utility (BR-MON-01..03, architecture.md §9).
 *
 * Amounts are decimal strings ("12.50") paired with a currency; the device only parses, validates
 * and formats. It never adds, divides, rounds or aggregates (BR-MON-02, CLAUDE.md §14): all totals
 * come from the backend.
 */
export interface Money {
    readonly amount: string;
    readonly currency: string;
}

const CURRENCY_RE = /^[A-Z]{3}$/;
const AMOUNT_RE = /^-?\d+(\.\d+)?$/;

export class MoneyError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "MoneyError";
    }
}

/** Number of fraction digits of an ISO 4217 currency (EUR: 2, JPY: 0), per the runtime ICU data. */
export function currencyDecimals(currency: string): number {
    assertCurrency(currency);
    return new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions()
        .maximumFractionDigits!;
}

function assertCurrency(currency: string): void {
    if (!CURRENCY_RE.test(currency)) throw new MoneyError("Invalid currency code");
}

/** Validates a decimal string against the currency's decimals; more decimals are rejected (BR-MON-03). */
export function parseAmount(amount: unknown, currency: string): string {
    if (typeof amount !== "string") throw new MoneyError("Amount must be a string, not a number");
    if (!AMOUNT_RE.test(amount)) throw new MoneyError("Amount is not a decimal string");
    const decimals = currencyDecimals(currency);
    const fraction = amount.split(".")[1] ?? "";
    if (fraction.length > decimals) throw new MoneyError("Too many decimals for the currency");
    return amount;
}

/** Decimal string -> integer minor units, as a bigint (no float involved). */
export function toMinorUnits(amount: string, currency: string): bigint {
    const valid = parseAmount(amount, currency);
    const decimals = currencyDecimals(currency);
    const negative = valid.startsWith("-");
    const [whole = "0", fraction = ""] = valid.replace("-", "").split(".");
    const minor = BigInt(whole + fraction.padEnd(decimals, "0"));
    return negative ? -minor : minor;
}

/** Integer minor units -> canonical decimal string with exactly the currency's decimals. */
export function fromMinorUnits(minor: bigint, currency: string): string {
    const decimals = currencyDecimals(currency);
    const negative = minor < 0n;
    const digits = (negative ? -minor : minor).toString().padStart(decimals + 1, "0");
    const whole = digits.slice(0, digits.length - decimals);
    const fraction = digits.slice(digits.length - decimals);
    return `${negative ? "-" : ""}${whole}${decimals > 0 ? `.${fraction}` : ""}`;
}

/**
 * Locale-aware display. Digits come from the exact decimal string (via bigint), never from a JS
 * number, so no precision is lost on any runtime; Intl only supplies the locale's symbol, separators
 * and pattern.
 */
export function formatMoney(money: Money, locale?: string): string {
    const minor = toMinorUnits(money.amount, money.currency);
    const decimals = currencyDecimals(money.currency);
    const negative = minor < 0n;
    const canonical = fromMinorUnits(negative ? -minor : minor, money.currency);
    const [whole = "0", fraction = ""] = canonical.split(".");
    const formatter = new Intl.NumberFormat(locale, {
        style: "currency",
        currency: money.currency,
    });
    const wholeText = new Intl.NumberFormat(locale, { useGrouping: true }).format(BigInt(whole));
    // Template value only selects the sign pattern; its digits are replaced below.
    const template = negative ? -1n : 1n;
    let integerDone = false;
    let result = "";
    for (const part of formatter.formatToParts(template)) {
        if (part.type === "integer" || part.type === "group") {
            if (!integerDone) result += wholeText;
            integerDone = true;
        } else if (part.type === "fraction") {
            result += fraction;
        } else if (part.type === "decimal" && decimals === 0) {
            continue;
        } else {
            result += part.value;
        }
    }
    return result;
}
