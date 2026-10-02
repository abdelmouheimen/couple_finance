package com.couplefinance.expense.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import com.couplefinance.household.api.TrackingStart;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Household tracking start maintained from expense events - Issue #23 (BR-ANA-03, BR-EXP-07). */
@IntegrationTest
class TrackingStartIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    TestTokens tokens;
    @Autowired
    TestUsers users;
    @Autowired
    JsonMapper jsonMapper;
    @Autowired
    TrackingStart trackingStart;

    @Test
    void BR_ANA_03_tracking_start_is_initialised_to_the_creation_date_and_lowered_by_an_earlier_shared_expense() {
        UUID user = users.active();
        HouseholdId household = createHousehold(user);
        LocalDate creation = trackingStart.of(household);

        post(user, creation.plusDays(1), "SHARED");
        assertThat(trackingStart.of(household)).as("a later expense does not change it").isEqualTo(creation);

        post(user, creation.minusDays(10), "SHARED");
        assertThat(trackingStart.of(household)).isEqualTo(creation.minusDays(10));

        post(user, creation.minusDays(3), "SHARED");
        assertThat(trackingStart.of(household)).as("never raised").isEqualTo(creation.minusDays(10));
    }

    @Test
    void BR_ANA_03_editing_a_shared_expense_to_an_earlier_date_lowers_it() {
        UUID user = users.active();
        HouseholdId household = createHousehold(user);
        LocalDate creation = trackingStart.of(household);
        String id = json(post(user, creation, "SHARED")).get("id").asString();

        MvcTestResult updated = mvc.put().uri("/api/v1/expenses/" + id)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content(body(user, creation.minusDays(20), "SHARED"))
                .exchange();

        assertThat(updated).hasStatus(HttpStatus.OK);
        assertThat(trackingStart.of(household)).isEqualTo(creation.minusDays(20));
    }

    @Test
    void BR_EXP_07_a_personal_expense_with_an_early_date_does_not_change_the_tracking_start() {
        UUID user = users.active();
        HouseholdId household = createHousehold(user);
        LocalDate creation = trackingStart.of(household);

        post(user, creation.minusDays(60), "PERSONAL");

        assertThat(trackingStart.of(household)).isEqualTo(creation);
    }

    @Test
    void BR_ANA_03_lowering_is_idempotent_and_order_independent() {
        HouseholdId household = createHousehold(users.active());
        LocalDate creation = trackingStart.of(household);

        assertThat(trackingStart.lowerTo(household, creation.minusDays(5))).isTrue();
        assertThat(trackingStart.lowerTo(household, creation.minusDays(5))).as("re-delivery").isFalse();
        assertThat(trackingStart.lowerTo(household, creation.minusDays(2))).as("out of order").isFalse();
        assertThat(trackingStart.lowerTo(household, creation)).isFalse();
        assertThat(trackingStart.of(household)).isEqualTo(creation.minusDays(5));
    }

    @Test
    void BR_ANA_03_an_expense_event_never_touches_another_household() {
        UUID user = users.active();
        HouseholdId mine = createHousehold(user);
        HouseholdId other = createHousehold(users.active());
        LocalDate otherBefore = trackingStart.of(other);

        post(user, trackingStart.of(mine).minusDays(30), "SHARED");

        assertThat(trackingStart.of(other)).isEqualTo(otherBefore);
    }

    private static String body(UUID user, LocalDate date, String sharing) {
        return "{\"amount\":{\"amount\":\"5.00\",\"currency\":\"EUR\"},\"date\":\"" + date + "\",\"paidByUserId\":\""
                + user + "\",\"sharingType\":\"" + sharing + "\",\"items\":[{\"categoryId\":\"" + GROCERIES
                + "\",\"amount\":{\"amount\":\"5.00\",\"currency\":\"EUR\"}}]}";
    }

    private MvcTestResult post(UUID user, LocalDate date, String sharing) {
        MvcTestResult result = mvc.post().uri("/api/v1/expenses")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content(body(user, date, sharing)).exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return result;
    }

    private HouseholdId createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Foyer\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return new HouseholdId(UUID.fromString(json(result).get("id").asString()));
    }

    private JsonNode json(MvcTestResult result) {
        return jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
