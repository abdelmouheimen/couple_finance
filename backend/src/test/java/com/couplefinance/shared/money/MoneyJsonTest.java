package com.couplefinance.shared.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.module.SimpleModule;

/**
 * JSON shape of {@link Money} (CLAUDE.md §10.2): {@code {"amount": "12.50", "currency": "EUR"}}, the amount
 * always a string. Uses a plain {@link JsonMapper} with a fake {@link CurrencyDecimals} — no Spring context
 * needed; {@code JdbcCurrencyDecimalsIntegrationTest} covers the real, household-backed wiring.
 */
class MoneyJsonTest {

    private static final CurrencyDecimals DECIMALS = currency -> switch (currency.value()) {
        case "EUR" -> 2;
        case "JPY" -> 0;
        case "TND" -> 3;
        default -> throw new IllegalArgumentException("Unknown currency: " + currency);
    };

    private final JsonMapper mapper = JsonMapper.builder()
            .addModule(new SimpleModule("MoneyModule")
                    .addSerializer(Money.class, new MoneySerializer())
                    .addDeserializer(Money.class, new MoneyDeserializer(DECIMALS)))
            .build();

    @Test
    void amount_is_serialized_as_a_decimal_string_not_a_json_number() {
        Money money = Money.parse("12.50", new CurrencyCode("EUR"), 2);

        String json = mapper.writeValueAsString(money);

        assertThat(json).isEqualTo("{\"amount\":\"12.50\",\"currency\":\"EUR\"}");
    }

    @Test
    void round_trips_through_json_for_a_zero_decimal_currency() {
        Money money = Money.parse("1500", new CurrencyCode("JPY"), 0);

        Money reparsed = mapper.readValue(mapper.writeValueAsString(money), Money.class);

        assertThat(reparsed).isEqualTo(money);
    }

    @Test
    void round_trips_through_json_for_a_three_decimal_currency() {
        Money money = Money.parse("12.505", new CurrencyCode("TND"), 3);

        Money reparsed = mapper.readValue(mapper.writeValueAsString(money), Money.class);

        assertThat(reparsed).isEqualTo(money);
    }

    @Test
    void BR_MON_03_deserialization_rejects_more_decimals_than_the_currency_allows() {
        String json = "{\"amount\": \"12.505\", \"currency\": \"EUR\"}";

        assertThatThrownBy(() -> mapper.readValue(json, Money.class))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.TOO_MANY_DECIMALS));
    }

    @Test
    void deserialization_rejects_a_numeric_amount_rather_than_a_string() {
        String json = "{\"amount\": 12.50, \"currency\": \"EUR\"}";

        assertThatThrownBy(() -> mapper.readValue(json, Money.class))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.INVALID_AMOUNT_FORMAT));
    }

    @Test
    void deserialization_rejects_a_missing_currency() {
        String json = "{\"amount\": \"12.50\"}";

        assertThatThrownBy(() -> mapper.readValue(json, Money.class))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.INVALID_AMOUNT_FORMAT));
    }

    @Test
    void deserialization_rejects_a_malformed_currency_code_as_a_client_error_not_a_server_error() {
        String json = "{\"amount\": \"12.50\", \"currency\": \"eur\"}";

        assertThatThrownBy(() -> mapper.readValue(json, Money.class))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(MoneyErrorCode.INVALID_CURRENCY_FORMAT));
    }
}
