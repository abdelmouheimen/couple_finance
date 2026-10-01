package com.couplefinance.categorization.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

/**
 * Household-scoped access to categories. "Visible" always means: a system category or one of the given
 * household. No unscoped finder and no delete exist (BR-CAT-03, security.md §4.1).
 */
public interface CategoryRepository extends Repository<Category, UUID> {

    Category saveAndFlush(Category category);

    @Query("select c from Category c where c.id = :id and (c.householdId is null or c.householdId = :householdId)")
    Optional<Category> findVisibleByIdAndHouseholdId(UUID id, UUID householdId);

    @Query("""
            select c from Category c
            where c.id in :ids and (c.householdId is null or c.householdId = :householdId)
            """)
    List<Category> findVisibleByIdsAndHouseholdId(Collection<UUID> ids, UUID householdId);

    /**
     * One page in listing order: system categories first, then the household's, each by sort order. Position
     * {@code (group, sortOrder)}; {@code sort_order} is unique within a group.
     */
    @Query("""
            select c from Category c
            where (c.householdId is null or c.householdId = :householdId)
              and (:includeArchived = true or c.archivedAt is null)
              and (case when c.householdId is null then 0 else 1 end > :afterGroup
                   or (case when c.householdId is null then 0 else 1 end = :afterGroup
                       and c.sortOrder > :afterSortOrder))
            order by case when c.householdId is null then 0 else 1 end, c.sortOrder
            limit :fetchSize
            """)
    List<Category> findPage(UUID householdId, boolean includeArchived, int afterGroup, int afterSortOrder,
                            int fetchSize);

    /** Case-insensitive name clash among the household's own categories (BR-CAT-02), archived ones included. */
    @Query("""
            select count(c) > 0 from Category c
            where c.householdId = :householdId and lower(c.name) = lower(:name)
            """)
    boolean nameExists(UUID householdId, String name);

    /** Same as {@link #nameExists} ignoring one category, so that a category can be renamed to its own name. */
    @Query("""
            select count(c) > 0 from Category c
            where c.householdId = :householdId and lower(c.name) = lower(:name) and c.id <> :excludedId
            """)
    boolean nameExistsExcluding(UUID householdId, String name, UUID excludedId);
}
