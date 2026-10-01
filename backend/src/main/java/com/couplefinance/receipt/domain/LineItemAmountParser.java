package com.couplefinance.receipt.domain;

import java.util.Objects;

import com.couplefinance.shared.money.Money;

/** Parses a line-item amount and enforces the sign rule of its type (BR-RCP-04). */
public final class LineItemAmountParser {

    private LineItemAmountParser() {
    }

    /** The sign is identical across readings, so a violation makes the whole value {@code INVALID}. */
    public static ParseResult<Money> parse(LineItemType type, String raw, Money maximum, AmountHints hints) {
        Objects.requireNonNull(type, "type");
        ParseResult<Money> result = AmountParser.parse(raw, maximum, hints);
        Money sample = switch (result) {
            case ParseResult.Resolved<Money> resolved -> resolved.value();
            case ParseResult.Ambiguous<Money> ambiguous -> ambiguous.candidates().get(0);
            case ParseResult.Invalid<Money> invalid -> null;
        };
        if (sample != null && !type.acceptsSign(sample)) {
            return new ParseResult.Invalid<>(InvalidReason.INVALID_SIGN);
        }
        return result;
    }
}
