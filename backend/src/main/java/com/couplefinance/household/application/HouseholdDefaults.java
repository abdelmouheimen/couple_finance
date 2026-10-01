package com.couplefinance.household.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings applied when a household is created without them (BR-HH-16). */
@ConfigurationProperties("couplefinance.household.defaults")
public record HouseholdDefaults(
        @DefaultValue("EUR") String currency,
        @DefaultValue("Europe/Paris") String timezone,
        @DefaultValue("1") int periodStartDay) {
}
