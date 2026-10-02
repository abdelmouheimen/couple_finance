package com.couplefinance.platform.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

/**
 * Cross-user privacy probes (security.md section 4.2, BR-EXP-07, BR-CAT-05): the partner of the owner must not observe
 * the owner's PERSONAL data through direct reads, lists, search, totals, counts or error messages. Each probe first
 * proves the owner does see the data, so a probe cannot pass vacuously.
 */
final class PrivacyProbes {

    private PrivacyProbes() {
    }

    static void expenseList(AuthzWorld world, SeededHousehold s) {
        // sanity: the owner's PERSONAL view contains the personal expense
        JsonNode own = world.body(world.get(s.owner(), "/api/v1/expenses?scope=PERSONAL&limit=100"));
        assertThat(own.get("totals").get("count").asLong()).isEqualTo(1);

        for (String scope : new String[] {"HOUSEHOLD", "PERSONAL"}) {
            MvcTestResult result = world.get(s.partner(), "/api/v1/expenses?scope=" + scope + "&limit=100");
            assertThat(result).hasStatus(HttpStatus.OK);
            assertNoPrivateData(s, AuthzWorld.text(result), "/api/v1/expenses");
            JsonNode body = world.body(result);
            JsonNode totals = body.get("totals");
            if (scope.equals("HOUSEHOLD")) {
                assertThat(totals.get("count").asLong()).as("count excludes PERSONAL").isEqualTo(1);
                assertThat(totals.get("net").get("amount").asString()).isEqualTo(SeededHousehold.SHARED_AMOUNT);
                assertThat(body.get("items")).hasSize(1);
                assertThat(body.get("items").get(0).get("id").asString()).isEqualTo(s.sharedExpense().toString());
            } else {
                assertThat(totals.get("count").asLong()).isZero();
                assertThat(totals.get("net").get("amount").asString()).isEqualTo("0.00");
                assertThat(body.get("items")).isEmpty();
            }
            // search by the private text must find nothing, for the partner
            for (String marker : new String[] {s.personalMerchant(), s.personalNote()}) {
                MvcTestResult search = world.get(s.partner(),
                        "/api/v1/expenses?scope=" + scope + "&limit=100&q=" + marker);
                assertThat(search).hasStatus(HttpStatus.OK);
                assertThat(world.body(search).get("totals").get("count").asLong()).isZero();
                assertThat(world.body(search).get("items")).isEmpty();
            }
        }
    }

    static void expenseDetail(AuthzWorld world, SeededHousehold s) {
        assertThat(world.get(s.owner(), "/api/v1/expenses/" + s.personalExpense())).hasStatus(HttpStatus.OK);
        assertIndistinguishableFromUnknown(world, s, "/api/v1/expenses/");
    }

    static void expenseAudit(AuthzWorld world, SeededHousehold s) {
        assertThat(world.get(s.owner(), "/api/v1/expenses/" + s.personalExpense() + "/audit"))
                .hasStatus(HttpStatus.OK);
        MvcTestResult result = world.get(s.partner(), "/api/v1/expenses/" + s.personalExpense() + "/audit");
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertNoPrivateData(s, AuthzWorld.text(result), "/api/v1/expenses/" + s.personalExpense() + "/audit");
    }

    static void expenseUpdate(AuthzWorld world, SeededHousehold s) {
        String uri = "/api/v1/expenses/" + s.personalExpense();
        MvcTestResult result = world.send(s.partner(), Call.withIfMatch(HttpMethod.PUT, uri,
                AuthzWorld.expenseBody(s.partner(), "PERSONAL", "Hijacked", null), "\"0\""));
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertNoPrivateData(s, AuthzWorld.text(result), uri);
        MvcTestResult unchanged = world.get(s.owner(), uri);
        assertThat(unchanged).hasStatus(HttpStatus.OK);
        assertThat(world.body(unchanged).get("merchant").asString()).isEqualTo(s.personalMerchant());
    }

    static void expenseDelete(AuthzWorld world, SeededHousehold s) {
        String uri = "/api/v1/expenses/" + s.personalExpense();
        MvcTestResult result = world.send(s.partner(), Call.without(HttpMethod.DELETE, uri));
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertNoPrivateData(s, AuthzWorld.text(result), uri);
        assertThat(world.get(s.owner(), uri)).hasStatus(HttpStatus.OK);
    }

    static void expenseRestore(AuthzWorld world, SeededHousehold s) {
        String uri = "/api/v1/expenses/" + s.personalExpense();
        assertThat(world.send(s.owner(), Call.without(HttpMethod.DELETE, uri))).hasStatus(HttpStatus.NO_CONTENT);
        MvcTestResult result = world.send(s.partner(), Call.without(HttpMethod.POST, uri + "/restore"));
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertNoPrivateData(s, AuthzWorld.text(result), uri);
        assertThat(world.get(s.owner(), uri)).as("still deleted: the partner's restore had no effect")
                .hasStatus(HttpStatus.NOT_FOUND);
    }

    static void suggestions(AuthzWorld world, SeededHousehold s) {
        String uri = "/api/v1/category-suggestions?merchant=" + s.ruleMerchant() + "&sharingType=";
        JsonNode own = world.body(world.get(s.owner(), uri + "PERSONAL"));
        assertThat(own.get("source").asString()).isEqualTo("USER_RULE");
        assertThat(own.get("categoryId").asString()).isEqualTo(s.customCategory().toString());

        for (String sharing : new String[] {"PERSONAL", "SHARED"}) {
            MvcTestResult result = world.get(s.partner(), uri + sharing);
            assertThat(result).hasStatus(HttpStatus.OK);
            JsonNode body = world.body(result);
            assertThat(body.get("source").asString()).isEqualTo("DEFAULT_OTHER");
            assertThat(body.get("categoryId").asString()).isNotEqualTo(s.customCategory().toString());
        }
    }

    private static void assertIndistinguishableFromUnknown(AuthzWorld world, SeededHousehold s, String base) {
        MvcTestResult personal = world.get(s.partner(), base + s.personalExpense());
        MvcTestResult unknown = world.get(s.partner(), base + UUID.randomUUID());
        assertThat(personal).hasStatus(HttpStatus.NOT_FOUND);
        assertNoPrivateData(s, AuthzWorld.text(personal), base + s.personalExpense());
        JsonNode a = world.body(personal);
        JsonNode b = world.body(unknown);
        assertThat(a.get("code")).isEqualTo(b.get("code"));
        assertThat(a.get("title")).isEqualTo(b.get("title"));
        assertThat(a.get("detail")).isEqualTo(b.get("detail"));
    }

    /** The request URI is echoed in the Problem {@code instance}: it is the caller's own input, not a leak. */
    static void assertNoPrivateData(SeededHousehold s, String responseText, String requestUri) {
        String text = responseText.replace(requestUri, "");
        for (String marker : s.privateMarkers()) {
            assertThat(text).as("response must not contain " + marker).doesNotContain(marker);
        }
    }
}
