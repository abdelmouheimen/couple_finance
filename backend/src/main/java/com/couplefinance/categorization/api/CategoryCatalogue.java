package com.couplefinance.categorization.api;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;

/**
 * Verification facade for modules that reference categories by id without a foreign key (expense items, budget
 * lines, receipt proposals; database-schema.md §6.1). A category is visible to a household when it is a system
 * category or one of the household's own; a category of another household is indistinguishable from an unknown
 * id (non-disclosure).
 */
public interface CategoryCatalogue {

    /**
     * BR-CAT-03 / BR-EXP-14: of the given ids, those that must NOT be used on a new or changed item — unknown,
     * belonging to another household, or archived. Empty when every id is usable.
     */
    Set<UUID> unusableCategories(HouseholdId household, Collection<UUID> categoryIds);

    /**
     * BR-EXP-14: of the given ids, those that do not exist for the household (unknown or of another household).
     * Archived categories exist: use this for references left unchanged on an existing item.
     */
    Set<UUID> unknownCategories(HouseholdId household, Collection<UUID> categoryIds);
}
