package com.couplefinance.household.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HouseholdTest {

    private static final HouseholdId ID = new HouseholdId(UUID.randomUUID());
    private static final UserId CREATOR = new UserId(UUID.randomUUID());
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    // 23:30 UTC on 31 March = 01:30 on 1 April in Paris.
    private static final Instant NOW = Instant.parse("2026-03-31T23:30:00Z");

    @Test
    void BR_HH_16_creator_becomes_the_first_active_member_on_seat_1() {
        Household household = create("Notre foyer", 1);

        assertThat(household.activeMembers()).singleElement().satisfies(member -> {
            assertThat(member.userId()).isEqualTo(CREATOR.value());
            assertThat(member.seat()).isEqualTo((short) 1);
            assertThat(member.joinedAt()).isEqualTo(NOW);
            assertThat(member.isActive()).isTrue();
        });
    }

    @Test
    void BR_HH_16_new_household_is_active_with_the_given_settings() {
        Household household = create("Notre foyer", 25);

        assertThat(household.id()).isEqualTo(ID);
        assertThat(household.name()).isEqualTo("Notre foyer");
        assertThat(household.currency()).isEqualTo("EUR");
        assertThat(household.timezone()).isEqualTo(PARIS);
        assertThat(household.status()).isEqualTo(HouseholdStatus.ACTIVE);
        assertThat(household.currentPeriodStartDay()).isEqualTo(25);
        assertThat(household.createdAt()).isEqualTo(NOW);
    }

    @Test
    void BR_HH_05_today_is_computed_in_the_household_timezone() {
        Household household = create("Notre foyer", 1);

        assertThat(household.trackingStartDate()).isEqualTo(LocalDate.of(2026, 4, 1));
    }

    @Test
    void BR_HH_16_name_is_trimmed() {
        assertThat(create("  Notre foyer \t", 1).name()).isEqualTo("Notre foyer");
    }

    @Test
    void BR_HH_16_name_of_exactly_100_characters_is_accepted() {
        assertThat(create("a".repeat(100), 1).name()).hasSize(100);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void BR_HH_16_blank_name_is_rejected(String name) {
        assertThatIllegalArgumentException().isThrownBy(() -> create(name, 1));
    }

    @Test
    void BR_HH_16_missing_name_is_rejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> create(null, 1));
    }

    @Test
    void BR_HH_16_name_longer_than_100_characters_is_rejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> create("a".repeat(101), 1));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 29, -1})
    void BR_HH_05_period_start_day_must_be_between_1_and_28(int startDay) {
        assertThatIllegalArgumentException().isThrownBy(() -> create("Notre foyer", startDay));
    }

    @ParameterizedTest
    @ValueSource(strings = {"eur", "EU", "EURO"})
    void BR_MON_01_currency_must_be_an_iso_code(String currency) {
        assertThatIllegalArgumentException().isThrownBy(() ->
                Household.create(ID, "Notre foyer", currency, PARIS, 1, CREATOR, NOW));
    }

    private static Household create(String name, int periodStartDay) {
        return Household.create(ID, name, "EUR", PARIS, periodStartDay, CREATOR, NOW);
    }
}
