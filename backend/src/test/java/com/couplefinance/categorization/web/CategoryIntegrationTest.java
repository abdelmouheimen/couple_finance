package com.couplefinance.categorization.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.categorization.api.CategoryCatalogue;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** {@code /api/v1/categories} and {@link CategoryCatalogue} — Issue #10 (BR-CAT-01, BR-CAT-02, BR-CAT-03). */
@IntegrationTest
class CategoryIntegrationTest {

    private static final String URI = "/api/v1/categories";
    private static final String GROCERIES = "019a0000-0000-7000-8000-000000000001";
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
    CategoryCatalogue catalogue;

    // ------------------------------------------------------------------ seed and listing

    @Test
    void BR_CAT_01_the_twelve_system_categories_are_seeded_with_codes_and_no_name() {
        UUID user = users.active();
        createHousehold(user);

        JsonNode page = json(list(user, ""));

        List<String> codes = new ArrayList<>();
        page.get("items").forEach(item -> {
            assertThat(item.get("type").asString()).isEqualTo("SYSTEM");
            assertThat(item.get("name").isNull()).isTrue();
            assertThat(item.get("archived").asBoolean()).isFalse();
            codes.add(item.get("systemCode").asString());
        });
        assertThat(codes).containsExactly("GROCERIES", "HOUSING", "UTILITIES", "TRANSPORT", "RESTAURANTS", "HEALTH",
                "LEISURE", "SHOPPING", "TRAVEL", "CHILDREN", "GIFTS", "OTHER").hasSize(12);
        assertThat(page.get("items").get(0).get("id").asString()).isEqualTo(GROCERIES);
    }

    @Test
    void listing_returns_system_then_own_categories_and_excludes_archived_by_default() {
        UUID user = users.active();
        createHousehold(user);
        JsonNode pets = json(create(user, "Pets"));
        create(user, "Gym");
        archive(user, pets.get("id").asString(), "\"0\"");

        List<String> defaultNames = names(json(list(user, "?limit=100")));
        List<String> allNames = names(json(list(user, "?limit=100&includeArchived=true")));

        assertThat(defaultNames).containsExactly("Gym");
        assertThat(allNames).containsExactly("Pets", "Gym");
        JsonNode archivedItem = json(list(user, "?limit=100&includeArchived=true")).get("items").get(12);
        assertThat(archivedItem.get("archived").asBoolean()).isTrue();
        assertThat(archivedItem.get("archivedAt").isNull()).isFalse();
    }

    @Test
    void listing_is_cursor_paginated() {
        UUID user = users.active();
        createHousehold(user);
        create(user, "Pets");

        JsonNode first = json(list(user, "?limit=10"));
        assertThat(first.get("items")).hasSize(10);
        String cursor = first.get("nextCursor").asString();
        JsonNode second = json(list(user, "?limit=10&cursor=" + cursor));

        assertThat(second.get("items")).hasSize(3);
        assertThat(second.get("items").get(0).get("systemCode").asString()).isEqualTo("GIFTS");
        assertThat(second.get("items").get(2).get("name").asString()).isEqualTo("Pets");
        assertThat(second.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void a_cursor_is_not_valid_for_another_listing_variant() {
        UUID user = users.active();
        createHousehold(user);
        String cursor = json(list(user, "?limit=2")).get("nextCursor").asString();

        assertThat(list(user, "?includeArchived=true&cursor=" + cursor)).hasStatus(HttpStatus.BAD_REQUEST);
    }

    // ------------------------------------------------------------------ create

    @Test
    void active_member_creates_a_custom_category_with_defaults() {
        UUID user = users.active();
        createHousehold(user);

        MvcTestResult result = create(user, "  Pets  ");

        assertThat(result).hasStatus(HttpStatus.CREATED).headers().hasValue(HttpHeaders.ETAG, "\"0\"");
        JsonNode body = json(result);
        assertThat(body.get("type").asString()).isEqualTo("CUSTOM");
        assertThat(body.get("name").asString()).isEqualTo("Pets");
        assertThat(body.get("icon").asString()).isEqualTo("tag");
        assertThat(body.get("color").asString()).isEqualTo("#9E9E9E");
        assertThat(body.get("sortOrder").asInt()).isEqualTo(1);
        assertThat(body.get("archived").asBoolean()).isFalse();
        assertThat(body.get("version").asLong()).isZero();
        assertThat(body.get("systemCode").isNull()).isTrue();
    }

    @Test
    void sort_order_is_appended_after_the_highest_one() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(json(create(user, "A")).get("sortOrder").asInt()).isEqualTo(1);
        assertThat(json(create(user, "B")).get("sortOrder").asInt()).isEqualTo(2);
        JsonNode c = json(create(user, "C"));
        archive(user, c.get("id").asString(), "\"0\"");
        assertThat(json(create(user, "D")).get("sortOrder").asInt()).isEqualTo(4);
    }

    @Test
    void BR_CAT_02_name_unique_case_insensitive_per_household() {
        UUID user = users.active();
        createHousehold(user);
        create(user, "Pets");

        assertThat(create(user, "pETS"))
                .hasStatus(HttpStatus.CONFLICT)
                .hasContentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_NAME_ALREADY_EXISTS");
        assertThat(create(user, "  PETS ")).hasStatus(HttpStatus.CONFLICT);
        assertThat(create(user, "Pets 2")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void BR_CAT_02_two_households_may_use_the_same_name() {
        UUID first = users.active();
        UUID second = users.active();
        createHousehold(first);
        createHousehold(second);

        assertThat(create(first, "Pets")).hasStatus(HttpStatus.CREATED);
        assertThat(create(second, "Pets")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void a_custom_name_may_equal_a_system_category_name() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(create(user, "Groceries")).hasStatus(HttpStatus.CREATED);
        assertThat(create(user, "GROCERIES")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void name_validation_rejects_blank_long_and_missing_names() {
        UUID user = users.active();
        createHousehold(user);

        for (String body : new String[] {"{\"name\": \"   \"}", "{\"name\": \"" + "x".repeat(41) + "\"}", "{}"}) {
            assertThat(post(user, body)).hasStatus(HttpStatus.BAD_REQUEST)
                    .bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        }
        assertThat(create(user, "x".repeat(40))).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void client_supplied_ownership_and_presentation_fields_are_ignored() {
        UUID user = users.active();
        UUID foreign = createHousehold(users.active());
        createHousehold(user);

        MvcTestResult result = post(user, "{\"name\": \"Pets\", \"householdId\": \"" + foreign
                + "\", \"systemCode\": \"HOUSING\", \"sortOrder\": 99, \"icon\": \"x\"}");

        // Unknown properties (mass assignment attempts) are rejected outright.
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(names(json(list(user, "?limit=100")))).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM categorization.category WHERE household_id = :id")
                .param("id", foreign).query(Integer.class).single()).isZero();
    }

    // ------------------------------------------------------------------ update

    @Test
    void member_renames_and_archives_and_unarchives_a_custom_category() {
        UUID user = users.active();
        createHousehold(user);
        String id = json(create(user, "Pets")).get("id").asString();

        MvcTestResult renamed = patch(user, id, "\"0\"", "{\"name\": \" Animals \"}");
        assertThat(renamed).hasStatus(HttpStatus.OK).headers().hasValue(HttpHeaders.ETAG, "\"1\"");
        assertThat(json(renamed).get("name").asString()).isEqualTo("Animals");

        MvcTestResult archived = patch(user, id, "\"1\"", "{\"archived\": true}");
        assertThat(archived).hasStatus(HttpStatus.OK);
        assertThat(json(archived).get("archived").asBoolean()).isTrue();
        assertThat(json(archived).get("version").asLong()).isEqualTo(2);

        MvcTestResult unarchived = patch(user, id, "\"2\"", "{\"archived\": false}");
        assertThat(json(unarchived).get("archived").asBoolean()).isFalse();
        assertThat(json(unarchived).get("archivedAt").isNull()).isTrue();
    }

    @Test
    void rename_to_the_same_name_in_another_case_is_allowed_but_a_clash_is_rejected() {
        UUID user = users.active();
        createHousehold(user);
        String pets = json(create(user, "Pets")).get("id").asString();
        create(user, "Gym");

        assertThat(patch(user, pets, "\"0\"", "{\"name\": \"PETS\"}")).hasStatus(HttpStatus.OK);
        assertThat(patch(user, pets, "\"1\"", "{\"name\": \"gym\"}")).hasStatus(HttpStatus.CONFLICT)
                .bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_NAME_ALREADY_EXISTS");
    }

    @Test
    void BR_CAT_02_an_archived_category_still_holds_its_name() {
        UUID user = users.active();
        createHousehold(user);
        String pets = json(create(user, "Pets")).get("id").asString();
        patch(user, pets, "\"0\"", "{\"archived\": true}");

        assertThat(create(user, "pets")).hasStatus(HttpStatus.CONFLICT);
    }

    @Test
    void BR_CAT_03_system_category_cannot_be_renamed_or_archived() {
        UUID user = users.active();
        createHousehold(user);

        assertThat(patch(user, GROCERIES, "\"0\"", "{\"archived\": true}")).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("SYSTEM_CATEGORY_IMMUTABLE");
        assertThat(patch(user, GROCERIES, "\"0\"", "{\"name\": \"Food\"}")).hasStatus(HttpStatus.FORBIDDEN);
        assertThat(jdbc.sql("SELECT archived_at IS NULL AND name IS NULL FROM categorization.category WHERE id = :id")
                .param("id", UUID.fromString(GROCERIES)).query(Boolean.class).single()).isTrue();
    }

    @Test
    void BR_CAT_03_the_database_refuses_to_archive_or_name_a_system_category() {
        assertThatThrownBy(() -> jdbc.sql("UPDATE categorization.category SET archived_at = now() WHERE id = :id")
                .param("id", UUID.fromString(OTHER)).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE categorization.category SET name = 'x' WHERE id = :id")
                .param("id", UUID.fromString(OTHER)).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void no_endpoint_hard_deletes_a_category() {
        UUID user = users.active();
        createHousehold(user);
        String id = json(create(user, "Pets")).get("id").asString();

        assertThat(mvc.delete().uri(URI + "/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange()).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(count(id)).isEqualTo(1);
    }

    @Test
    void database_enforces_case_insensitive_name_uniqueness() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        create(user, "Pets");

        assertThatThrownBy(() -> insertRaw(household, user, "PETS", 50))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(household, user, "Other", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRaw(household, user, " padded ", 60))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void update_requires_a_valid_current_if_match() {
        UUID user = users.active();
        createHousehold(user);
        String id = json(create(user, "Pets")).get("id").asString();

        assertThat(patch(user, id, null, "{\"name\": \"A\"}")).hasStatus(HttpStatus.PRECONDITION_REQUIRED)
                .bodyJson().extractingPath("$.code").isEqualTo("IF_MATCH_REQUIRED");
        assertThat(patch(user, id, "W/\"0\"", "{\"name\": \"A\"}")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("IF_MATCH_INVALID");
        assertThat(patch(user, id, "\"7\"", "{\"name\": \"A\"}")).hasStatus(HttpStatus.PRECONDITION_FAILED)
                .bodyJson().extractingPath("$.code").isEqualTo("VERSION_CONFLICT");
        assertThat(names(json(list(user, "?limit=100")))).containsExactly("Pets");
    }

    @Test
    void a_stale_version_is_rejected_after_a_concurrent_change() {
        UUID user = users.active();
        createHousehold(user);
        String id = json(create(user, "Pets")).get("id").asString();
        patch(user, id, "\"0\"", "{\"name\": \"Animals\"}");

        assertThat(patch(user, id, "\"0\"", "{\"archived\": true}")).hasStatus(HttpStatus.PRECONDITION_FAILED);
    }

    @Test
    void update_needs_at_least_one_field() {
        UUID user = users.active();
        createHousehold(user);
        String id = json(create(user, "Pets")).get("id").asString();

        assertThat(patch(user, id, "\"0\"", "{}")).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    // ------------------------------------------------------------------ authorization

    @Test
    void unauthenticated_requests_are_rejected() {
        assertThat(mvc.get().uri(URI).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri(URI).contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"A\"}")
                .exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.patch().uri(URI + "/" + GROCERIES).contentType(MediaType.APPLICATION_JSON)
                .content("{\"archived\": true}").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void a_user_without_household_gets_404() {
        UUID user = users.active();

        assertThat(list(user, "")).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_NOT_FOUND");
        assertThat(create(user, "Pets")).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void a_category_of_another_household_is_invisible_and_returns_404_before_if_match_is_checked() {
        UUID owner = users.active();
        UUID outsider = users.active();
        createHousehold(owner);
        createHousehold(outsider);
        String id = json(create(owner, "Private")).get("id").asString();

        assertThat(names(json(list(outsider, "?limit=100&includeArchived=true")))).isEmpty();
        assertThat(patch(outsider, id, "\"0\"", "{\"name\": \"Hacked\"}")).hasStatus(HttpStatus.NOT_FOUND)
                .bodyJson().extractingPath("$.code").isEqualTo("RESOURCE_NOT_FOUND");
        // 404 comes first, even without or with a malformed If-Match.
        assertThat(patch(outsider, id, null, "{\"name\": \"Hacked\"}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(patch(outsider, id, "garbage", "{\"archived\": true}")).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(patch(outsider, UUID.randomUUID().toString(), "\"0\"", "{\"archived\": true}"))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(names(json(list(owner, "?limit=100")))).containsExactly("Private");
    }

    @Test
    void BR_HH_10_archive_reader_can_list_but_not_write() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        String id = json(create(user, "Pets")).get("id").asString();
        dissolve(household, user);

        assertThat(names(json(list(user, "?limit=100")))).containsExactly("Pets");
        assertThat(create(user, "Gym")).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(patch(user, id, "\"0\"", "{\"archived\": true}")).hasStatus(HttpStatus.FORBIDDEN)
                .bodyJson().extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(count(id)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ concurrency

    @Test
    void concurrent_creations_get_distinct_sort_orders() throws Exception {
        UUID user = users.active();
        createHousehold(user);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<MvcTestResult>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String name = "Concurrent " + i;
                tasks.add(() -> create(user, name));
            }
            List<Integer> orders = new ArrayList<>();
            for (Future<MvcTestResult> future : pool.invokeAll(tasks)) {
                MvcTestResult result = future.get();
                assertThat(result).hasStatus(HttpStatus.CREATED);
                orders.add(json(result).get("sortOrder").asInt());
            }
            assertThat(orders).doesNotHaveDuplicates().containsExactlyInAnyOrder(1, 2, 3, 4, 5, 6, 7, 8);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void BR_CAT_02_concurrent_creations_of_the_same_name_yield_one_winner() throws Exception {
        UUID user = users.active();
        createHousehold(user);
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<MvcTestResult>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                String name = i % 2 == 0 ? "Same" : "SAME";
                tasks.add(() -> create(user, name));
            }
            int created = 0;
            int conflicts = 0;
            for (Future<MvcTestResult> future : pool.invokeAll(tasks)) {
                int status = future.get().getResponse().getStatus();
                if (status == 201) {
                    created++;
                } else if (status == 409) {
                    conflicts++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicts).isEqualTo(threads - 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void database_rejects_a_duplicate_sort_order_in_a_household() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        create(user, "Pets");

        assertThatThrownBy(() -> insertRaw(household, user, "Other name", 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ categorization.api

    @Test
    void catalogue_accepts_system_and_own_active_categories_for_new_items() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID own = UUID.fromString(json(create(user, "Pets")).get("id").asString());

        assertThat(catalogue.unusableCategories(new HouseholdId(household),
                Set.of(UUID.fromString(GROCERIES), UUID.fromString(OTHER), own))).isEmpty();
        assertThat(catalogue.unusableCategories(new HouseholdId(household), Set.of())).isEmpty();
    }

    @Test
    void catalogue_refuses_unknown_archived_and_foreign_categories_for_new_items() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID otherUser = users.active();
        createHousehold(otherUser);
        UUID archived = UUID.fromString(json(create(user, "Old")).get("id").asString());
        patch(user, archived.toString(), "\"0\"", "{\"archived\": true}");
        UUID foreign = UUID.fromString(json(create(otherUser, "Foreign")).get("id").asString());
        UUID unknown = UUID.randomUUID();

        assertThat(catalogue.unusableCategories(new HouseholdId(household), Set.of(archived, foreign, unknown)))
                .containsExactlyInAnyOrder(archived, foreign, unknown);
    }

    @Test
    void BR_EXP_14_catalogue_knows_archived_categories_but_not_foreign_or_unknown_ones() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID otherUser = users.active();
        createHousehold(otherUser);
        UUID archived = UUID.fromString(json(create(user, "Old")).get("id").asString());
        patch(user, archived.toString(), "\"0\"", "{\"archived\": true}");
        UUID foreign = UUID.fromString(json(create(otherUser, "Foreign")).get("id").asString());
        UUID unknown = UUID.randomUUID();

        assertThat(catalogue.unknownCategories(new HouseholdId(household),
                Set.of(archived, UUID.fromString(GROCERIES), foreign, unknown)))
                .containsExactlyInAnyOrder(foreign, unknown);
    }

    // ------------------------------------------------------------------ helpers

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Foyer\"}")
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private MvcTestResult list(UUID user, String query) {
        return mvc.get().uri(URI + query).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
    }

    private MvcTestResult create(UUID user, String name) {
        return post(user, "{\"name\": \"" + name + "\"}");
    }

    private MvcTestResult post(UUID user, String body) {
        return mvc.post().uri(URI).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body).exchange();
    }

    private MvcTestResult patch(UUID user, String id, String ifMatch, String body) {
        var request = mvc.patch().uri(URI + "/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (ifMatch != null) {
            request = request.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return request.exchange();
    }

    private MvcTestResult archive(UUID user, String id, String ifMatch) {
        return patch(user, id, ifMatch, "{\"archived\": true}");
    }

    private JsonNode json(MvcTestResult result) {
        return jsonMapper.readTree(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8));
    }

    /** Names of the custom categories of a page. */
    private static List<String> names(JsonNode page) {
        List<String> names = new ArrayList<>();
        page.get("items").forEach(item -> {
            if (!item.get("name").isNull()) {
                names.add(item.get("name").asString());
            }
        });
        return names;
    }

    private int count(String id) {
        return jdbc.sql("SELECT count(*) FROM categorization.category WHERE id = :id")
                .param("id", UUID.fromString(id)).query(Integer.class).single();
    }

    private void insertRaw(UUID household, UUID actor, String name, int sortOrder) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO categorization.category (id, household_id, name, icon, color, sort_order,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (:id, :household, :name, 'tag', '#9E9E9E', :sortOrder, :now, :actor, :now, :actor)
                        """)
                .param("id", UUID.randomUUID()).param("household", household).param("name", name)
                .param("sortOrder", sortOrder).param("now", now).param("actor", actor).update();
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
