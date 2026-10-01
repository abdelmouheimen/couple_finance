package com.couplefinance.categorization.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.junit.jupiter.api.Test;

/** Domain rules of the Category aggregate: BR-CAT-01, BR-CAT-02 (name format), BR-CAT-03. */
class CategoryTest {

    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant LATER = Instant.parse("2026-10-02T10:00:00Z");
    private static final HouseholdId HOUSEHOLD = new HouseholdId(UUID.randomUUID());
    private static final UserId USER = new UserId(UUID.randomUUID());

    private static Category custom(String name) {
        return Category.createCustom(UUID.randomUUID(), HOUSEHOLD, name, 1, USER, NOW);
    }

    private static Category system() throws ReflectiveOperationException {
        Constructor<Category> constructor = Category.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Category category = constructor.newInstance();
        set(category, "id", UUID.randomUUID());
        set(category, "systemCode", "GROCERIES");
        return category;
    }

    private static void set(Category category, String field, Object value) throws ReflectiveOperationException {
        Field f = Category.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(category, value);
    }

    private static String code(Throwable e) {
        return ((ApplicationException) e).errorCode().code();
    }

    @Test
    void BR_CAT_01_custom_category_is_active_with_documented_defaults() {
        Category category = custom("  Pets ");

        assertThat(category.name()).isEqualTo("Pets");
        assertThat(category.isSystem()).isFalse();
        assertThat(category.isArchived()).isFalse();
        assertThat(category.systemCode()).isNull();
        assertThat(category.icon()).isEqualTo("tag");
        assertThat(category.color()).isEqualTo("#9E9E9E");
        assertThat(category.householdId()).isEqualTo(HOUSEHOLD.value());
    }

    @Test
    void name_must_have_1_to_40_characters_after_trimming() {
        assertThat(custom("x".repeat(40)).name()).hasSize(40);
        assertThat(custom(" " + "x".repeat(40) + " ").name()).hasSize(40);
        for (String invalid : new String[] {null, "", "   ", "x".repeat(41)}) {
            assertThatThrownBy(() -> custom(invalid)).satisfies(e -> assertThat(code(e)).isEqualTo("VALIDATION_FAILED"));
        }
    }

    @Test
    void rename_trims_and_records_the_actor() {
        Category category = custom("Pets");
        UserId other = new UserId(UUID.randomUUID());

        category.rename("  Animals ", other, LATER);

        assertThat(category.name()).isEqualTo("Animals");
    }

    @Test
    void archive_and_unarchive_are_idempotent() {
        Category category = custom("Pets");

        category.archive(USER, NOW);
        category.archive(USER, LATER);
        assertThat(category.archivedAt()).isEqualTo(NOW);

        category.unarchive(USER, LATER);
        category.unarchive(USER, LATER);
        assertThat(category.isArchived()).isFalse();
    }

    @Test
    void BR_CAT_03_system_category_cannot_be_archived() throws ReflectiveOperationException {
        Category category = system();

        assertThat(category.isSystem()).isTrue();
        assertThatThrownBy(() -> category.archive(USER, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SYSTEM_CATEGORY_IMMUTABLE"));
        assertThatThrownBy(() -> category.unarchive(USER, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SYSTEM_CATEGORY_IMMUTABLE"));
        assertThat(category.isArchived()).isFalse();
    }

    @Test
    void BR_CAT_03_system_category_cannot_be_renamed() throws ReflectiveOperationException {
        Category category = system();

        assertThatThrownBy(() -> category.rename("Mine", USER, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo("SYSTEM_CATEGORY_IMMUTABLE"));
        assertThat(category.name()).isNull();
    }

    @Test
    void sort_order_beyond_the_smallint_range_is_rejected() {
        assertThatThrownBy(() -> Category.createCustom(UUID.randomUUID(), HOUSEHOLD, "Pets",
                Category.MAX_SORT_ORDER + 1, USER, NOW))
                .satisfies(e -> assertThat(code(e)).isEqualTo("CATEGORY_LIMIT_REACHED"));
    }
}
