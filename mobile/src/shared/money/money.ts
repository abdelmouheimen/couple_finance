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
 * Locale conventions for integer digits, read from Intl with small, exactly representable JS numbers
 * only. Hermes' Intl.NumberFormat (Expo Go / React Native) rejects bigint arguments with
 * "Cannot convert BigInt to number", and a JS number cannot carry every amount exactly, so the digits
 * of an amount are never handed to Intl: Intl only supplies the locale's numerals, group separator and
 * grouping sizes, which are then applied to the exact decimal string.
 */
interface IntegerConventions {
    readonly numerals: readonly string[];
    readonly separator: string;
    readonly primary: number;
    readonly secondary: number;
    readonly minimumGrouping: number;
}

const conventionsCache = new Map<string, IntegerConventions>();

function integerConventions(locale: string | undefined): IntegerConventions {
    const key = locale ?? "";
    const cached = conventionsCache.get(key);
    if (cached) return cached;
    const plain = new Intl.NumberFormat(locale, { useGrouping: false, maximumFractionDigits: 0 });
    const numerals = Array.from({ length: 10 }, (_, d) => plain.format(d));
    const grouped = new Intl.NumberFormat(locale, { useGrouping: true, maximumFractionDigits: 0 });
    // 123456789: 9 digits expose both the primary (last) and the secondary group size (en-IN: 12,34,56,789).
    const parts = grouped.formatToParts(123456789);
    const sizes = parts.filter((p) => p.type === "integer").map((p) => Array.from(p.value).length);
    const separator = parts.find((p) => p.type === "group")?.value ?? "";
    const primary = sizes.length > 1 ? sizes[sizes.length - 1]! : 0;
    const secondary = sizes.length > 2 ? sizes[sizes.length - 2]! : primary;
    // Some locales (es, pl) do not group a 4-digit integer: minimum grouping digits 2.
    const groupsFourDigits = grouped.formatToParts(1000).some((p) => p.type === "group");
    const conventions = {
        numerals,
        separator,
        primary,
        secondary,
        minimumGrouping: groupsFourDigits ? 1 : 2,
    };
    conventionsCache.set(key, conventions);
    return conventions;
}

function localizeDigits(digits: string, numerals: readonly string[]): string {
    return Array.from(digits, (d) => numerals[d.charCodeAt(0) - 48] ?? d).join("");
}

/** Groups an ASCII digit string with the locale's separator and group sizes, in string space only. */
function groupInteger(whole: string, c: IntegerConventions): string {
    if (c.primary === 0 || c.separator === "" || whole.length < c.primary + c.minimumGrouping) {
        return localizeDigits(whole, c.numerals);
    }
    const groups = [whole.slice(-c.primary)];
    let rest = whole.slice(0, -c.primary);
    while (rest.length > c.secondary) {
        groups.unshift(rest.slice(-c.secondary));
        rest = rest.slice(0, -c.secondary);
    }
    if (rest !== "") groups.unshift(rest);
    return groups.map((g) => localizeDigits(g, c.numerals)).join(c.separator);
}

/** Exact absolute whole/fraction digit strings of an amount (canonical decimals) and its sign. */
function splitAmount(money: Money): { negative: boolean; whole: string; fraction: string } {
    const minor = toMinorUnits(money.amount, money.currency);
    const negative = minor < 0n;
    const canonical = fromMinorUnits(negative ? -minor : minor, money.currency);
    const [whole = "0", fraction = ""] = canonical.split(".");
    return { negative, whole, fraction };
}

/**
 * Locale-aware display. Digits come from the exact decimal string, never from a JS number or a
 * bigint passed to Intl (unsupported by Hermes), so no precision is lost on any runtime; Intl only
 * supplies the locale's symbol, separators, numerals and sign/currency pattern.
 */
export function formatMoney(money: Money, locale?: string): string {
    const { negative, whole, fraction } = splitAmount(money);
    const conventions = integerConventions(locale);
    const formatter = new Intl.NumberFormat(locale, {
        style: "currency",
        currency: money.currency,
    });
    // The template value (exactly representable) only selects the sign/currency pattern; its digits
    // are replaced below.
    let integerDone = false;
    let result = "";
    for (const part of formatter.formatToParts(negative ? -1 : 1)) {
        if (part.type === "integer" || part.type === "group") {
            if (!integerDone) result += groupInteger(whole, conventions);
            integerDone = true;
        } else if (part.type === "fraction") {
            result += localizeDigits(fraction, conventions.numerals);
        } else if (part.type === "decimal" && fraction === "") {
            continue;
        } else {
            result += part.value;
        }
    }
    return result;
}

/**
 * Number used only to select the plural form of the currency name ("1 euro" / "2 euros"). Integers up
 * to 15 digits are exact; longer ones keep their last 6 digits behind a leading 1 so every CLDR integer
 * plural rule (n % 10, % 100, % 1000, % 1000000) selects the same category. The spoken digits
 * themselves always come from the exact string.
 */
function pluralSelector(whole: string): number {
    return whole.length <= 15 ? Number(whole) : Number(`1${whole.slice(-6)}`);
}

/**
 * Screen-reader text for an amount: "12 euros 50" (whole units, currency name, minor units), built
 * from the exact decimal string; no amount is converted to a JS number or passed to Intl as a bigint.
 */
export function spokenMoney(money: Money, locale = "en"): string {
    const { negative, whole, fraction } = splitAmount(money);
    const name =
        new Intl.NumberFormat(locale, {
            style: "currency",
            currency: money.currency,
            currencyDisplay: "name",
            minimumFractionDigits: 0,
            maximumFractionDigits: 0,
        })
            .formatToParts(pluralSelector(whole))
            .find((p) => p.type === "currency")?.value ?? money.currency;
    const cents = fraction !== "" && /[1-9]/.test(fraction) ? ` ${fraction.replace(/^0/, "")}` : "";
    return `${negative ? "minus " : ""}${whole} ${name}${cents}`;
}
