package com.couplefinance.platform.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds fixtures through the public API (test builders only where the API does not exist yet: second member,
 * dissolution) and sends calls with real signed tokens. Uses the shared {@code @IntegrationTest} context.
 */
public final class AuthzWorld {

    /** Fixed business date: the matrix does not depend on wall-clock time. */
    static final String EXPENSE_DATE = "2025-06-15";

    private final MockMvcTester mvc;
    private final TestTokens tokens;
    private final TestUsers users;
    private final JdbcClient jdbc;
    private final JsonMapper json;

    public AuthzWorld(MockMvcTester mvc, TestTokens tokens, TestUsers users, JdbcClient jdbc, JsonMapper json) {
        this.mvc = mvc;
        this.tokens = tokens;
        this.users = users;
        this.jdbc = jdbc;
        this.json = json;
    }

    public static String expenseBody(UUID paidBy, String sharing, @Nullable String merchant, @Nullable String note) {
        return "{\"amount\":{\"amount\":\"" + SeededHousehold.SHARED_AMOUNT + "\",\"currency\":\"EUR\"},\"date\":\""
                + EXPENSE_DATE + "\",\"paidByUserId\":\"" + paidBy + "\",\"sharingType\":\"" + sharing
                + "\",\"items\":[{\"categoryId\":\"" + SeededHousehold.SYSTEM_GROCERIES + "\",\"amount\":{\"amount\":\""
                + SeededHousehold.SHARED_AMOUNT + "\",\"currency\":\"EUR\"}}]"
                + (merchant == null ? "" : ",\"merchant\":\"" + merchant + "\"")
                + (note == null ? "" : ",\"note\":\"" + note + "\"") + "}";
    }

    public UUID newUser() {
        return users.active();
    }

    /**
     * A household with two members, an invitation of the owner, a custom category, SHARED and PERSONAL expenses and a
     * PERSONAL rule.
     */
    public SeededHousehold seedHousehold() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        // BR-HH-04: only a single-member household can invite, so the invitation is created before the partner joins.
        MvcTestResult invitation = send(owner, Call.without(HttpMethod.POST, "/api/v1/households/me/invitations"));
        assertThat(invitation).hasStatus(HttpStatus.CREATED);
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", partner)
                .update();
        String tag = UUID.randomUUID().toString().substring(0, 8);

        MvcTestResult category = send(owner, Call.with(HttpMethod.POST, "/api/v1/categories",
                "{\"name\":\"Custom " + tag + "\"}"));
        assertThat(category).hasStatus(HttpStatus.CREATED);
        UUID customCategory = id(category);

        String personalMerchant = "Pmerchant" + tag;
        String personalNote = "Pnote" + tag;
        String ruleMerchant = "Rmerchant" + tag;
        MvcTestResult shared = send(owner, Call.with(HttpMethod.POST, "/api/v1/expenses",
                expenseBody(owner, "SHARED", "Smerchant" + tag, null)));
        assertThat(shared).hasStatus(HttpStatus.CREATED);
        MvcTestResult personal = send(owner, Call.with(HttpMethod.POST, "/api/v1/expenses",
                expenseBody(owner, "PERSONAL", personalMerchant, personalNote)));
        assertThat(personal).hasStatus(HttpStatus.CREATED);
        MvcTestResult rule = send(owner, Call.with(HttpMethod.PUT, "/api/v1/merchant-rules",
                "{\"merchant\":\"" + ruleMerchant + "\",\"categoryId\":\"" + customCategory
                        + "\",\"sharingType\":\"PERSONAL\"}"));
        assertThat(rule).hasStatus(HttpStatus.OK);
        return new SeededHousehold(household, owner, partner, id(invitation), customCategory, id(shared), id(personal),
                personalMerchant, personalNote, ruleMerchant);
    }

    /** BR-HH-10: dissolution does not exist yet as an endpoint; same state change as the production lifecycle. */
    public void dissolve(SeededHousehold seeded) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now)
                .param("id", seeded.household()).update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id").param("now", now).param("until", now.plusDays(30))
                .param("id", seeded.household()).update();
    }

    /** Fingerprint of every household-owned row of the business tables: detects any mutation by a foreign caller. */
    public String snapshot(SeededHousehold seeded) {
        StringBuilder all = new StringBuilder();
        for (String table : new String[] {"expense.expense", "expense.expense_item", "expense.audit_event",
                "categorization.category", "categorization.merchant_rule", "household.invitation"}) {
            all.append(jdbc.sql("SELECT coalesce(string_agg(t::text, '|' ORDER BY t::text), '') FROM " + table
                    + " t WHERE t.household_id = :h").param("h", seeded.household()).query(String.class).single());
        }
        return all.toString();
    }

    /** Sends {@code call} as {@code user}; no Authorization header when {@code user} is {@code null}. */
    public MvcTestResult send(@Nullable UUID user, Call call) {
        var request = mvc.method(call.method()).uri(call.uri());
        if (user != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(user));
        }
        if (call.ifMatch() != null) {
            request = request.header(HttpHeaders.IF_MATCH, call.ifMatch());
        }
        if (call.body() != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(call.body());
        }
        return request.exchange();
    }

    public MvcTestResult get(UUID user, String uri) {
        return send(user, Call.get(uri));
    }

    public static String text(MvcTestResult result) {
        try {
            return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public JsonNode body(MvcTestResult result) {
        return json.readTree(text(result));
    }

    private UUID id(MvcTestResult result) {
        return UUID.fromString(body(result).get("id").asString());
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = send(user, Call.with(HttpMethod.POST, "/api/v1/households", "{\"name\":\"Foyer\"}"));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return id(result);
    }
}
