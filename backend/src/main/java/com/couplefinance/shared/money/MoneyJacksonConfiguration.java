package com.couplefinance.shared.money;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;

/**
 * Registers {@link Money} (de)serialization with the application's {@code JsonMapper} (CLAUDE.md §10.2), so
 * that any future {@code <module>.web} DTO can embed a {@code Money} field and get the canonical JSON shape for
 * free. {@link CurrencyDecimals} is implemented by the {@code household} module; Spring wires whichever bean
 * provides it.
 */
@Configuration(proxyBeanMethods = false)
public class MoneyJacksonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer moneyJsonMapperBuilderCustomizer(CurrencyDecimals currencyDecimals) {
        SimpleModule module = new SimpleModule("MoneyModule");
        module.addSerializer(Money.class, new MoneySerializer());
        module.addDeserializer(Money.class, new MoneyDeserializer(currencyDecimals));
        return builder -> builder.addModule(module);
    }
}
