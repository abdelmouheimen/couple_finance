import { assert, bigInt, constantFrom, property } from "fast-check";
import {
    MoneyError,
    currencyDecimals,
    formatMoney,
    fromMinorUnits,
    parseAmount,
    toMinorUnits,
} from "./money";

describe("money (BR-MON-02, BR-MON-03)", () => {
    test("currency decimals follow the currency", () => {
        expect(currencyDecimals("EUR")).toBe(2);
        expect(currencyDecimals("JPY")).toBe(0);
    });

    test("parses decimal strings to minor units", () => {
        expect(toMinorUnits("12.50", "EUR")).toBe(1250n);
        expect(toMinorUnits("12.5", "EUR")).toBe(1250n);
        expect(toMinorUnits("0.05", "EUR")).toBe(5n);
        expect(toMinorUnits("150", "JPY")).toBe(150n);
        expect(toMinorUnits("-3.10", "EUR")).toBe(-310n);
    });

    test("formats minor units to canonical strings", () => {
        expect(fromMinorUnits(1250n, "EUR")).toBe("12.50");
        expect(fromMinorUnits(5n, "EUR")).toBe("0.05");
        expect(fromMinorUnits(150n, "JPY")).toBe("150");
        expect(fromMinorUnits(-310n, "EUR")).toBe("-3.10");
    });

    test("rejects floats / numbers", () => {
        expect(() => parseAmount(12.5, "EUR")).toThrow(MoneyError);
        expect(() => parseAmount(0.1 + 0.2, "EUR")).toThrow(MoneyError);
    });

    test("rejects malformed strings and exponent notation", () => {
        for (const bad of ["", "abc", "1e3", "1,50", " 1.00", "1.", ".5", "--1"]) {
            expect(() => parseAmount(bad, "EUR")).toThrow(MoneyError);
        }
    });

    test("rejects more decimals than the currency allows instead of rounding", () => {
        expect(() => parseAmount("1.001", "EUR")).toThrow(MoneyError);
        expect(() => parseAmount("1.5", "JPY")).toThrow(MoneyError);
    });

    test("rejects an invalid currency code", () => {
        expect(() => parseAmount("1.00", "eur")).toThrow(MoneyError);
    });

    test("formats for display without float conversion", () => {
        expect(formatMoney({ amount: "1234.50", currency: "EUR" }, "en-US")).toBe("€1,234.50");
        expect(formatMoney({ amount: "12345678901234567.89", currency: "EUR" }, "en-US")).toBe(
            "€12,345,678,901,234,567.89",
        );
    });

    test("formats negatives and zero-decimal currencies", () => {
        expect(formatMoney({ amount: "-0.50", currency: "EUR" }, "en-US")).toBe("-€0.50");
        expect(formatMoney({ amount: "-1234.00", currency: "EUR" }, "en-US")).toBe("-€1,234.00");
        expect(formatMoney({ amount: "1500", currency: "JPY" }, "en-US")).toBe("¥1,500");
    });

    test("property: minor units -> string -> minor units round-trips", () => {
        assert(
            property(
                bigInt({ min: -(10n ** 18n), max: 10n ** 18n }),
                constantFrom("EUR", "JPY", "USD"),
                (minor, currency) =>
                    toMinorUnits(fromMinorUnits(minor, currency), currency) === minor,
            ),
        );
    });

    test("property: canonical string -> minor units -> string round-trips", () => {
        assert(
            property(bigInt({ min: 0n, max: 10n ** 18n }), (minor) => {
                const s = fromMinorUnits(minor, "EUR");
                return fromMinorUnits(toMinorUnits(s, "EUR"), "EUR") === s;
            }),
        );
    });
});
