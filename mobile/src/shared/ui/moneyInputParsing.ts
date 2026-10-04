/**
 * Pure string logic for MoneyInput (BR-MON-01..03): no number type is ever involved, so no float can
 * appear. The canonical form uses "." and at most `decimals` fraction digits.
 */

/** Keeps digits and a single decimal separator ("." or ","), truncated to `decimals` fraction digits. */
export function sanitizeAmount(raw: string, decimals: number): string {
    let whole = "";
    let fraction = "";
    let seenSeparator = false;
    for (const ch of raw) {
        if (ch >= "0" && ch <= "9") {
            if (seenSeparator) {
                if (fraction.length < decimals) fraction += ch;
            } else {
                whole += ch;
            }
        } else if ((ch === "." || ch === ",") && !seenSeparator && decimals > 0) {
            seenSeparator = true;
        }
    }
    whole = whole.replace(/^0+(?=\d)/, "");
    if (whole === "" && seenSeparator) whole = "0";
    return seenSeparator ? `${whole}.${fraction}` : whole;
}

/** Canonical emitted value: sanitized, with a dangling separator dropped ("12." -> "12"). */
export function canonicalAmount(raw: string, decimals: number): string {
    const clean = sanitizeAmount(raw, decimals);
    return clean.endsWith(".") ? clean.slice(0, -1) : clean;
}

/** Pads the fraction to the currency's decimals on blur ("12.5" -> "12.50"); empty stays empty. */
export function padAmount(canonical: string, decimals: number): string {
    if (canonical === "" || decimals === 0) return canonical;
    const [whole = "", fraction = ""] = canonical.split(".");
    return `${whole}.${fraction.padEnd(decimals, "0")}`;
}

/** Canonical -> text shown to the user, using the locale's decimal separator. */
export function toDisplay(canonical: string, separator: string): string {
    return canonical.replace(".", separator);
}

/** Decimal separator of a locale, derived from Intl (no hard-coded locale table). */
export function decimalSeparator(locale?: string): string {
    return (
        new Intl.NumberFormat(locale).formatToParts(1.1).find((p) => p.type === "decimal")?.value ??
        "."
    );
}
