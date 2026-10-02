package com.couplefinance.categorization.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.categorization.api.LearnedSave;
import com.couplefinance.categorization.api.MerchantNames;
import com.couplefinance.categorization.api.MerchantRuleLearning;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Merchant rules and rule-based suggestions - Issue #12 (BR-CAT-04, BR-CAT-05, BR-EXP-07). Learning is exercised
 * end to end through {@code POST /api/v1/expenses} (the expense-saved event) and directly through
 * {@link MerchantRuleLearning} for re-delivery and concurrency.
 */
@IntegrationTest
class MerchantRuleIntegrationTest {

    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
    private static final String HOUSING = "019a0000-0000-7000-8000-000000000002";
    private static final String OTHER = "019a0000-0000-7000-8000-000000000012";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    MerchantRuleLearning learning;

    // ------------------------------------------------------------------ suggestion (BR-CAT-04)

    @Test
    void BR_CAT_04_without_a_rule_the_suggestion_is_other() {
        UUID user = users.active();
        createHousehold(user);

        JsonNode suggestion = json(suggest(user, "Boulangerie Paul", "SHARED"));

        assertThat(suggestion.get("categoryId").asString()).isEqualTo(OTHER);
        assertThat(suggestion.get("source").asString()).isEqualTo("DEFAULT_OTHER");
    }

    @Test
    void BR_CAT_04_an_explicit_household_rule_is_suggested_for_the_normalised_merchant() {
        UUID user = users.active();
        createHousehold(user);
        assertThat(putRule(user, "Carrefour City 0123", GROCERIES, "SHARED")).hasStatus(HttpStatus.OK);

        JsonNode suggestion = json(suggest(user, "CARREFOUR CITY", "SHARED"));

        assertThat(suggestion.get("categoryId").asString()).isEqualTo(GROCERIES);
        assertThat(suggestion.get("source").asString()).isEqualTo("HOUSEHOLD_RULE");
    }

    @Test
    void BR_CAT_04_personal_user_rule_wins_over_household_rule_and_is_invisible_to_the_partner() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);
        putRule(owner, "Pharmacie Lune", GROCERIES, "SHARED");
        assertThat(putRule(owner, "Pharmacie Lune", HOUSING, "PERSONAL")).hasStatus(HttpStatus.OK);

        assertThat(json(suggest(owner, "Pharmacie Lune", "PERSONAL")).get("source").asString())
                .isEqualTo("USER_RULE");
        assertThat(json(suggest(owner, "Pharmacie Lune", "SHARED")).get("source").asString())
                .isEqualTo("HOUSEHOLD_RULE");
        JsonNode seenByPartner = json(suggest(partner, "Pharmacie Lune", "PERSONAL"));
        assertThat(seenByPartner.get("source").asString()).isEqualTo("HOUSEHOLD_RULE");
        assertThat(seenByPartner.get("categoryId").asString()).isEqualTo(GROCERIES);
    }

    @Test
    void BR_EXP_07_a_personal_rule_never_leaks_to_the_partner_even_without_a_household_rule() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);
        putRule(owner, "Boutique Secrete", HOUSING, "PERSONAL");

        JsonNode partnerView = json(suggest(partner, "Boutique Secrete", "PERSONAL"));

        assertThat(partnerView.get("source").asString()).isEqualTo("DEFAULT_OTHER");
        assertThat(partnerView.get("categoryId").asString()).isEqualTo(OTHER);
        assertThat(json(suggest(partner, "Boutique Secrete", "SHARED")).get("source").asString())
                .isEqualTo("DEFAULT_OTHER");
    }

    @Test
    void BR_CAT_04_a_rule_pointing_to_an_archived_category_is_not_suggested() {
        UUID user = users.active();
        createHousehold(user);
        JsonNode pets = createCategory(user, "Animaux");
        putRule(user, "Animalerie", pets.get("id").asString(), "SHARED");
        assertThat(json(suggest(user, "Animalerie", "SHARED")).get("source").asString()).isEqualTo("HOUSEHOLD_RULE");

        archive(user, pets);

        assertThat(json(suggest(user, "Animalerie", "SHARED")).get("source").asString()).isEqualTo("DEFAULT_OTHER");
    }

    @Test
    void rules_of_another_household_are_never_suggested() {
        UUID owner = users.active();
        UUID outsider = users.active();
        createHousehold(owner);
        createHousehold(outsider);
        putRule(owner, "Leroy Merlin", HOUSING, "SHARED");

        assertThat(json(suggest(outsider, "Leroy Merlin", "SHARED")).get("source").asString())
                .isEqualTo("DEFAULT_OTHER");
    }

    @Test
    void a_blank_or_too_long_merchant_is_rejected_and_an_information_free_one_suggests_other() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(suggest(user, "x".repeat(121), "SHARED")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(suggest(user, "***", "SHARED")).hasStatus(HttpStatus.OK);
        assertThat(mvc.get().uri("/api/v1/category-suggestions?merchant=a&sharingType=BOTH")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange()).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ explicit rule (BR-CAT-05)

    @Test
    void BR_CAT_05_explicit_rule_replaces_the_previous_one_and_is_idempotent() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        putRule(user, "Netto", GROCERIES, "SHARED");
        putRule(user, "Netto", GROCERIES, "SHARED");

        MvcTestResult second = putRule(user, "Netto", HOUSING, "SHARED");

        assertThat(second).hasStatus(HttpStatus.OK);
        assertThat(json(second).get("merchantKey").asString()).isEqualTo("netto");
        assertThat(ruleCount(household, "netto")).isEqualTo(1);
        assertThat(json(suggest(user, "Netto", "SHARED")).get("categoryId").asString()).isEqualTo(HOUSING);
    }

    @Test
    void explicit_rule_with_unknown_foreign_or_archived_category_or_empty_merchant_is_rejected() {
        UUID user = users.active();
        UUID outsider = users.active();
        createHousehold(user);
        createHousehold(outsider);
        String foreign = createCategory(outsider, "Prive").get("id").asString();
        JsonNode archived = createCategory(user, "Ancienne");
        archive(user, archived);

        assertThat(putRule(user, "Netto", UUID.randomUUID().toString(), "SHARED")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(putRule(user, "Netto", foreign, "SHARED")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(putRule(user, "Netto", archived.get("id").asString(), "SHARED"))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(putRule(user, "***", GROCERIES, "SHARED")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(mvc.put().uri("/api/v1/merchant-rules").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchant\":\"a\",\"categoryId\":\"" + GROCERIES
                        + "\",\"sharingType\":\"SHARED\",\"ownerUserId\":\"" + UUID.randomUUID() + "\"}")
                .exchange()).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void a_user_without_household_gets_404_and_an_unauthenticated_caller_401() {
        UUID user = users.active();

        assertThat(suggest(user, "Netto", "SHARED")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(putRule(user, "Netto", GROCERIES, "SHARED")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/v1/category-suggestions?merchant=a&sharingType=SHARED").exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.put().uri("/api/v1/merchant-rules").contentType(MediaType.APPLICATION_JSON)
                .content("{}").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_HH_10_a_dissolved_household_rejects_rule_writes_but_still_suggests() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        putRule(user, "Netto", GROCERIES, "SHARED");
        dissolve(household, user);

        assertThat(putRule(user, "Netto", HOUSING, "SHARED")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(json(suggest(user, "Netto", "SHARED")).get("categoryId").asString()).isEqualTo(GROCERIES);
    }

    // ------------------------------------------------------------------ learning (BR-CAT-05)

    @Test
    void BR_CAT_05_two_consecutive_shared_saves_with_the_same_category_create_a_household_rule() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        assertThat(createExpense(user, "MONOPRIX 0123", GROCERIES, "SHARED")).hasStatus(HttpStatus.CREATED);
        assertThat(ruleCount(household, "monoprix")).isZero();
        assertThat(streak(household, "monoprix")).isEqualTo(1);
        assertThat(createExpense(user, "Monoprix", GROCERIES, "SHARED")).hasStatus(HttpStatus.CREATED);

        assertThat(ruleCount(household, "monoprix")).isEqualTo(1);
        assertThat(streak(household, "monoprix")).isZero();
        JsonNode suggestion = json(suggest(user, "MONOPRIX", "SHARED"));
        assertThat(suggestion.get("categoryId").asString()).isEqualTo(GROCERIES);
        assertThat(suggestion.get("source").asString()).isEqualTo("HOUSEHOLD_RULE");
    }

    @Test
    void BR_CAT_05_a_save_with_another_category_restarts_the_streak() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        createExpense(user, "Fnac", GROCERIES, "SHARED");
        createExpense(user, "Fnac", HOUSING, "SHARED");

        assertThat(ruleCount(household, "fnac")).isZero();
        assertThat(streak(household, "fnac")).isEqualTo(1);
        createExpense(user, "Fnac", HOUSING, "SHARED");
        assertThat(ruleCount(household, "fnac")).isEqualTo(1);
    }

    @Test
    void BR_CAT_05_following_the_suggestion_breaks_the_streak() {
        UUID user = users.active();
        UUID household = createHousehold(user);

        createExpense(user, "Darty", GROCERIES, "SHARED"); // differs from Other: streak 1
        createExpense(user, "Darty", OTHER, "SHARED"); // follows the suggestion (Other): streak broken
        createExpense(user, "Darty", GROCERIES, "SHARED"); // streak restarts at 1

        assertThat(ruleCount(household, "darty")).isZero();
        assertThat(streak(household, "darty")).isEqualTo(1);
    }

    @Test
    void BR_CAT_05_household_rules_never_learn_from_personal_expenses_and_the_partner_is_not_influenced() {
        UUID owner = users.active();
        UUID partner = users.active();
        UUID household = createHousehold(owner);
        addMember(household, partner);

        createExpense(owner, "Lingerie Chantal", HOUSING, "PERSONAL");
        createExpense(owner, "Lingerie Chantal", HOUSING, "PERSONAL");

        assertThat(ruleCount(household, "lingerie chantal")).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM categorization.merchant_rule WHERE household_id = :h "
                + "AND owner_user_id = :o AND merchant_key = 'lingerie chantal'").param("h", household)
                .param("o", owner).query(Integer.class).single()).isEqualTo(1);
        assertThat(json(suggest(owner, "Lingerie Chantal", "PERSONAL")).get("source").asString())
                .isEqualTo("USER_RULE");
        assertThat(json(suggest(owner, "Lingerie Chantal", "SHARED")).get("source").asString())
                .isEqualTo("DEFAULT_OTHER");
        assertThat(json(suggest(partner, "Lingerie Chantal", "PERSONAL")).get("source").asString())
                .isEqualTo("DEFAULT_OTHER");
    }

    @Test
    void learning_ignores_expenses_without_merchant_or_with_several_items() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String multi = "[" + item(GROCERIES, "5.00") + "," + item(HOUSING, "5.00") + "]";

        for (int i = 0; i < 2; i++) {
            post(user, body("10.00", user, "SHARED", multi, "Multi Shop"));
            post(user, body("10.00", user, "SHARED", "[" + item(GROCERIES, "10.00") + "]", null));
        }

        assertThat(jdbc.sql("SELECT count(*) FROM categorization.merchant_correction WHERE household_id = :h")
                .param("h", household).query(Integer.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM categorization.merchant_rule WHERE household_id = :h")
                .param("h", household).query(Integer.class).single()).isZero();
    }

    @Test
    void BR_CAT_05_redelivery_of_the_same_saved_expense_is_idempotent() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        LearnedSave save = save(household, null, "Boulanger", GROCERIES, UUID.randomUUID());

        learning.learn(save);
        learning.learn(save);
        learning.learn(save);

        assertThat(ruleCount(household, "boulanger")).isZero();
        assertThat(streak(household, "boulanger")).isEqualTo(1);

        learning.learn(save(household, null, "Boulanger", GROCERIES, UUID.randomUUID()));
        assertThat(ruleCount(household, "boulanger")).isEqualTo(1);
        learning.learn(save); // late re-delivery of the first save: nothing changes
        assertThat(ruleCount(household, "boulanger")).isEqualTo(1);
        assertThat(streak(household, "boulanger")).isZero();
    }

    @Test
    void learning_ignores_an_archived_or_foreign_category() {
        UUID user = users.active();
        UUID outsider = users.active();
        UUID household = createHousehold(user);
        createHousehold(outsider);
        JsonNode archived = createCategory(user, "Vieille");
        archive(user, archived);
        UUID foreign = UUID.fromString(createCategory(outsider, "Etrangere").get("id").asString());

        for (int i = 0; i < 2; i++) {
            learning.learn(save(household, null, "Ikea", UUID.fromString(archived.get("id").asString()),
                    UUID.randomUUID()));
            learning.learn(save(household, null, "Ikea", foreign, UUID.randomUUID()));
        }

        assertThat(ruleCount(household, "ikea")).isZero();
        assertThat(streak(household, "ikea")).isZero();
    }

    @Test
    void BR_CAT_05_concurrent_saves_create_exactly_one_rule_without_error() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                tasks.add(() -> {
                    learning.learn(save(household, null, "Concurrent Shop", GROCERIES, UUID.randomUUID()));
                    return null;
                });
            }
            for (Future<Void> future : pool.invokeAll(tasks)) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(ruleCount(household, "concurrent shop")).isEqualTo(1);
        assertThat(streak(household, "concurrent shop")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private static LearnedSave save(UUID household, UUID owner, String merchant, String category, UUID expense) {
        return save(household, owner, merchant, UUID.fromString(category), expense);
    }

    private static LearnedSave save(UUID household, UUID owner, String merchant, UUID category, UUID expense) {
        return new LearnedSave(new HouseholdId(household), owner == null ? null : new UserId(owner),
                MerchantNames.normalise(merchant), category, expense);
    }

    private int ruleCount(UUID household, String key) {
        return jdbc.sql("SELECT count(*) FROM categorization.merchant_rule WHERE household_id = :h "
                + "AND owner_user_id IS NULL AND merchant_key = :k").param("h", household).param("k", key)
                .query(Integer.class).single();
    }

    private int streak(UUID household, String key) {
        return jdbc.sql("SELECT coalesce(max(consecutive_count), 0) FROM categorization.merchant_correction "
                + "WHERE household_id = :h AND owner_user_id IS NULL AND merchant_key = :k").param("h", household)
                .param("k", key).query(Integer.class).single();
    }

    private MvcTestResult suggest(UUID user, String merchant, String sharing) {
        return mvc.get().uri("/api/v1/category-suggestions").queryParam("merchant", merchant)
                .queryParam("sharingType", sharing).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    private MvcTestResult putRule(UUID user, String merchant, String category, String sharing) {
        return mvc.put().uri("/api/v1/merchant-rules").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"merchant\":\"" + merchant + "\",\"categoryId\":\"" + category
                        + "\",\"sharingType\":\"" + sharing + "\"}")
                .exchange();
    }

    private static String item(String category, String amount) {
        return "{\"categoryId\":\"" + category + "\",\"amount\":{\"amount\":\"" + amount
                + "\",\"currency\":\"EUR\"}}";
    }

    private static String body(String amount, UUID paidBy, String sharing, String items, String merchant) {
        return "{\"amount\":{\"amount\":\"" + amount + "\",\"currency\":\"EUR\"},\"date\":\""
                + LocalDate.now(ZoneId.of("Europe/Paris")) + "\",\"paidByUserId\":\"" + paidBy
                + "\",\"sharingType\":\"" + sharing + "\",\"items\":" + items
                + (merchant == null ? "" : ",\"merchant\":\"" + merchant + "\"") + "}";
    }

    private MvcTestResult createExpense(UUID user, String merchant, String category, String sharing) {
        return post(user, body("10.00", user, sharing, "[" + item(category, "10.00") + "]", merchant));
    }

    private MvcTestResult post(UUID user, String body) {
        return mvc.post().uri("/api/v1/expenses").header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body).exchange();
    }

    private JsonNode json(MvcTestResult result) {
        assertThat(result).hasStatusOk();
        try {
            return jsonMapper.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Foyer\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        try {
            return UUID.fromString(jsonMapper.readTree(result.getResponse().getContentAsString()).get("id")
                    .asString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode createCategory(UUID user, String name) {
        MvcTestResult result = mvc.post().uri("/api/v1/categories")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"" + name + "\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        try {
            return jsonMapper.readTree(result.getResponse().getContentAsString());
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void archive(UUID user, JsonNode category) {
        assertThat(mvc.patch().uri("/api/v1/categories/" + category.get("id").asString())
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).header(HttpHeaders.IF_MATCH, "\"0\"")
                .contentType(MediaType.APPLICATION_JSON).content("{\"archived\": true}").exchange())
                .hasStatus(HttpStatus.OK);
    }

    /** Joining by invitation does not exist yet: seeds the second member directly. */
    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", user).update();
    }

    private void dissolve(UUID household, UUID member) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now,
                            purge_at = :now + interval '90 days' WHERE id = :id
                        """).param("now", now).param("id", household).update();
        jdbc.sql("""
                        UPDATE household.household_member SET left_at = :now, archive_access_until = :until
                        WHERE household_id = :id AND user_id = :user
                        """).param("now", now).param("until", now.plusDays(30)).param("id", household)
                .param("user", member).update();
    }
}
