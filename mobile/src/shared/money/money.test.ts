import { assert, bigInt, constantFrom, property } from "fast-check";
import {
    spokenMoney,
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

describe("spokenMoney (accessibility)", () => {
    test("announces whole units, currency name and minor units without floats", () => {
        expect(spokenMoney({ amount: "12.50", currency: "EUR" }, "en")).toBe("12 euros 50");
        expect(spokenMoney({ amount: "1.00", currency: "EUR" }, "en")).toBe("1 euro");
        expect(spokenMoney({ amount: "0.05", currency: "EUR" }, "en")).toBe("0 euros 5");
        expect(spokenMoney({ amount: "-3.20", currency: "EUR" }, "en")).toBe("minus 3 euros 20");
    });
});

/**
 * Regression: on a physical Android phone (Expo Go / Hermes) MoneyText crashed with
 * "Cannot convert BigInt to number" because formatMoney passed a bigint to Intl.NumberFormat. Node's
 * Intl accepts bigint, so the Jest suite never saw it. This shim reproduces Hermes' behaviour.
 */
function withHermesIntl<T>(run: () => T): T {
    const RealNumberFormat = Intl.NumberFormat;
    const rejectBigInt = (value: unknown) => {
        if (typeof value === "bigint") throw new TypeError("Cannot convert BigInt to number");
    };
    const spy = jest
        .spyOn(Intl, "NumberFormat")
        .mockImplementation(
            (locales?: Intl.LocalesArgument, options?: Intl.NumberFormatOptions) => {
                const real = new RealNumberFormat(locales, options);
                return {
                    format: (value: number) => {
                        rejectBigInt(value);
                        return real.format(value);
                    },
                    formatToParts: (value: number) => {
                        rejectBigInt(value);
                        return real.formatToParts(value);
                    },
                    resolvedOptions: () => real.resolvedOptions(),
                } as Intl.NumberFormat;
            },
        );
    try {
        return run();
    } finally {
        spy.mockRestore();
    }
}

/** Currencies seeded as supported by the backend (household.currency) with their minor units. */
const SUPPORTED_CURRENCIES: readonly [string, number][] = [
    ["EUR", 2],
    ["USD", 2],
    ["GBP", 2],
    ["CHF", 2],
    ["CAD", 2],
    ["SEK", 2],
    ["NOK", 2],
    ["DKK", 2],
    ["PLN", 2],
    ["JPY", 0],
    ["MAD", 2],
    ["TND", 3],
];

const NNBSP = " "; // fr-FR group separator
const NBSP = " "; // fr-FR space before the currency symbol

describe("formatMoney on Hermes (Expo Go): no bigint reaches Intl", () => {
    test("the shim reproduces the device failure", () => {
        expect(() => withHermesIntl(() => new Intl.NumberFormat("en").format(1n as never))).toThrow(
            "Cannot convert BigInt to number",
        );
    });

    test("MoneyText amounts render: zero, 1.00, 12.34, negative (en-US)", () => {
        withHermesIntl(() => {
            expect(formatMoney({ amount: "0.00", currency: "EUR" }, "en-US")).toBe("€0.00");
            expect(formatMoney({ amount: "0", currency: "EUR" }, "en-US")).toBe("€0.00");
            expect(formatMoney({ amount: "1.00", currency: "EUR" }, "en-US")).toBe("€1.00");
            expect(formatMoney({ amount: "12.34", currency: "EUR" }, "en-US")).toBe("€12.34");
            expect(formatMoney({ amount: "-12.34", currency: "EUR" }, "en-US")).toBe("-€12.34");
            expect(formatMoney({ amount: "1234567.89", currency: "EUR" }, "en-US")).toBe(
                "€1,234,567.89",
            );
        });
    });

    test("fr-FR formatting", () => {
        withHermesIntl(() => {
            expect(formatMoney({ amount: "0.00", currency: "EUR" }, "fr-FR")).toBe(`0,00${NBSP}€`);
            expect(formatMoney({ amount: "1.00", currency: "EUR" }, "fr-FR")).toBe(`1,00${NBSP}€`);
            expect(formatMoney({ amount: "12.34", currency: "EUR" }, "fr-FR")).toBe(
                `12,34${NBSP}€`,
            );
            expect(formatMoney({ amount: "-12.34", currency: "EUR" }, "fr-FR")).toBe(
                `-12,34${NBSP}€`,
            );
            expect(formatMoney({ amount: "1234567.89", currency: "EUR" }, "fr-FR")).toBe(
                `1${NNBSP}234${NNBSP}567,89${NBSP}€`,
            );
        });
    });

    test("values above Number.MAX_SAFE_INTEGER keep every digit", () => {
        // 9223372036854775807 = the largest BIGINT minor-unit amount the backend can store.
        withHermesIntl(() => {
            expect(formatMoney({ amount: "92233720368547758.07", currency: "EUR" }, "en-US")).toBe(
                "€92,233,720,368,547,758.07",
            );
            expect(formatMoney({ amount: "-92233720368547758.07", currency: "EUR" }, "fr-FR")).toBe(
                `-92${NNBSP}233${NNBSP}720${NNBSP}368${NNBSP}547${NNBSP}758,07${NBSP}€`,
            );
            expect(formatMoney({ amount: "9007199254740993", currency: "JPY" }, "en-US")).toBe(
                "¥9,007,199,254,740,993",
            );
            expect(formatMoney({ amount: "9007199254740993.001", currency: "TND" }, "en-US")).toBe(
                "TND 9,007,199,254,740,993.001",
            );
        });
    });

    test("every supported currency renders its own fraction digits", () => {
        withHermesIntl(() => {
            for (const [currency, decimals] of SUPPORTED_CURRENCIES) {
                expect(currencyDecimals(currency)).toBe(decimals);
                const formatted = formatMoney({ amount: "1234", currency }, "en-US");
                const expected = new Intl.NumberFormat("en-US", { style: "currency", currency });
                expect(formatted).toBe(expected.format(1234));
                expect(formatted).toMatch(
                    decimals === 0 ? /1,234$/ : new RegExp(`1,234\.${"0".repeat(decimals)}`),
                );
            }
        });
    });

    test("property: matches Intl's own currency formatting wherever a JS number is exact", () => {
        // Oracle only: Number(amount) is exact for these <= 15-significant-digit test values.
        const locales = ["en-US", "fr-FR", "de-DE", "de-CH", "es-ES", "en-IN", "ja-JP", "ar-EG"];
        assert(
            property(
                bigInt({ min: -(10n ** 14n), max: 10n ** 14n }),
                constantFrom(...SUPPORTED_CURRENCIES.map(([c]) => c)),
                constantFrom(...locales),
                (minor, currency, locale) => {
                    const amount = fromMinorUnits(minor, currency);
                    const expected = new Intl.NumberFormat(locale, {
                        style: "currency",
                        currency,
                    }).format(Number(amount));
                    return (
                        withHermesIntl(() => formatMoney({ amount, currency }, locale)) === expected
                    );
                },
            ),
        );
    });

    test("spokenMoney (MoneyText accessibility label path) works too", () => {
        withHermesIntl(() => {
            expect(spokenMoney({ amount: "12.34", currency: "EUR" }, "en")).toBe("12 euros 34");
            expect(spokenMoney({ amount: "1.00", currency: "EUR" }, "en")).toBe("1 euro");
            expect(spokenMoney({ amount: "92233720368547758.07", currency: "EUR" }, "en")).toBe(
                "92233720368547758 euros 7",
            );
        });
    });
});
