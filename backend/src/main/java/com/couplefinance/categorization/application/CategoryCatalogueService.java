package com.couplefinance.categorization.application;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.categorization.api.CategoryCatalogue;
import com.couplefinance.categorization.domain.Category;
import com.couplefinance.categorization.domain.CategoryRepository;
import com.couplefinance.shared.id.HouseholdId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Implements the verification facade of {@code categorization.api}; read-only, always household-scoped. */
@Service
class CategoryCatalogueService implements CategoryCatalogue {

    private final CategoryRepository categories;

    CategoryCatalogueService(CategoryRepository categories) {
        this.categories = categories;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> unusableCategories(HouseholdId household, Collection<UUID> categoryIds) {
        Set<UUID> unusable = new HashSet<>(categoryIds);
        visible(household, unusable).stream().filter(category -> !category.isArchived())
                .forEach(category -> unusable.remove(category.id()));
        return unusable;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> unknownCategories(HouseholdId household, Collection<UUID> categoryIds) {
        Set<UUID> unknown = new HashSet<>(categoryIds);
        visible(household, unknown).forEach(category -> unknown.remove(category.id()));
        return unknown;
    }

    private List<Category> visible(HouseholdId household, Set<UUID> ids) {
        return ids.isEmpty() ? List.of() : categories.findVisibleByIdsAndHouseholdId(ids, household.value());
    }
}
