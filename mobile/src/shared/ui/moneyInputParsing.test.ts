import { assert, constantFrom, property, string, stringMatching, tuple } from "fast-check";
import {
    canonicalAmount,
    decimalSeparator,
    padAmount,
    sanitizeAmount,
    toDisplay,
} from "./moneyInputParsing";

test("BR_MON_01_keeps_digits_and_one_separator_only", () => {
    expect(sanitizeAmount("1a2b.3c", 2)).toBe("12.3");
    expect(sanitizeAmount("12,5", 2)).toBe("12.5");
    expect(sanitizeAmount("-5", 2)).toBe("5");
    expect(sanitizeAmount("1e5", 2)).toBe("15");
});

test("BR_MON_03_truncates_to_currency_decimals", () => {
    expect(sanitizeAmount("1.239", 2)).toBe("1.23");
    expect(sanitizeAmount("1.5", 0)).toBe("15");
});

test("leading zeros, lone separator and dangling separator", () => {
    expect(sanitizeAmount("007", 2)).toBe("7");
    expect(sanitizeAmount(".5", 2)).toBe("0.5");
    expect(canonicalAmount("12.", 2)).toBe("12");
    expect(canonicalAmount("", 2)).toBe("");
});

test("pads on blur", () => {
    expect(padAmount("12.5", 2)).toBe("12.50");
    expect(padAmount("12", 2)).toBe("12.00");
    expect(padAmount("", 2)).toBe("");
    expect(padAmount("12", 0)).toBe("12");
});

test("locale separator", () => {
    expect(decimalSeparator("fr-FR")).toBe(",");
    expect(decimalSeparator("en-US")).toBe(".");
});

test("property: canonical string round-trips through the display text and is idempotent", () => {
    const amount = tuple(
        stringMatching(/^(0|[1-9][0-9]{0,11})$/),
        stringMatching(/^[0-9]{0,2}$/),
    ).map(([w, f]) => (f === "" ? w : `${w}.${f}`));
    assert(
        property(amount, constantFrom(".", ","), (value, sep) => {
            expect(canonicalAmount(toDisplay(value, sep), 2)).toBe(value);
            expect(canonicalAmount(value, 2)).toBe(canonicalAmount(canonicalAmount(value, 2), 2));
        }),
    );
});

test("property: output only ever holds digits and at most one dot", () => {
    assert(
        property(string(), (raw) => {
            expect(sanitizeAmount(raw, 2)).toMatch(/^\d*(\.\d{0,2})?$/);
        }),
    );
});

test("BR_MON_01_pasted_grouped_input_is_not_truncated", () => {
    expect(sanitizeAmount("1,234.50", 2)).toBe("1234.50");
    expect(sanitizeAmount("1.234,50", 2)).toBe("1234.50");
    expect(sanitizeAmount("1,234,567", 2)).toBe("1234.56");
});
