package com.couplefinance.shared.money;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

/**
 * Serializes {@link Money} as {@code {"amount": "12.50", "currency": "EUR"}} — the amount is a string, never a
 * JSON number (CLAUDE.md §10.2).
 */
public final class MoneySerializer extends ValueSerializer<Money> {

    @Override
    public void serialize(Money value, JsonGenerator generator, SerializationContext context) {
        generator.writeStartObject();
        generator.writeStringProperty("amount", value.toDecimalString());
        generator.writeStringProperty("currency", value.currency().value());
        generator.writeEndObject();
    }
}
