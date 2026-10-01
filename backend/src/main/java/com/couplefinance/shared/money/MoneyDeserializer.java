package com.couplefinance.shared.money;

import com.couplefinance.shared.error.ApplicationException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;

/**
 * Deserializes {@code {"amount": "12.50", "currency": "EUR"}} into a {@link Money} (CLAUDE.md §10.2). Decimals
 * are resolved per currency through {@link CurrencyDecimals} so that BR-MON-03 (reject, never round, excess
 * decimals) is enforced on every inbound amount.
 */
public final class MoneyDeserializer extends ValueDeserializer<Money> {

    private final CurrencyDecimals currencyDecimals;

    public MoneyDeserializer(CurrencyDecimals currencyDecimals) {
        this.currencyDecimals = currencyDecimals;
    }

    @Override
    public Money deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode node = context.readTree(parser);
        JsonNode amountNode = node != null ? node.get("amount") : null;
        JsonNode currencyNode = node != null ? node.get("currency") : null;
        if (amountNode == null || !amountNode.isString() || currencyNode == null || !currencyNode.isString()) {
            throw new ApplicationException(MoneyErrorCode.INVALID_AMOUNT_FORMAT,
                    "Money must be an object with string \"amount\" and \"currency\" properties.");
        }
        CurrencyCode currency;
        try {
            currency = new CurrencyCode(currencyNode.stringValue());
        } catch (IllegalArgumentException e) {
            throw new ApplicationException(MoneyErrorCode.INVALID_CURRENCY_FORMAT,
                    "Currency must be a 3-letter ISO 4217 code such as \"EUR\".");
        }
        int decimals = currencyDecimals.decimalsOf(currency);
        return Money.parse(amountNode.stringValue(), currency, decimals);
    }
}
