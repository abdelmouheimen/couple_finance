package com.couplefinance.categorization.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.couplefinance.categorization.domain.Category;
import com.couplefinance.categorization.domain.CategoryErrorCode;
import com.couplefinance.categorization.domain.CategoryRepository;
import com.couplefinance.categorization.domain.SortOrderAllocator;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.shared.concurrency.VersionETag;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.error.CommonErrorCode;
import com.couplefinance.shared.id.UuidV7;
import com.couplefinance.shared.pagination.CursorCodec;
import com.couplefinance.shared.pagination.CursorPage;
import com.couplefinance.shared.pagination.PageQuery;
import org.hibernate.exception.ConstraintViolationException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases of the category vocabulary (BR-CAT-01..03). The household always comes from the authenticated user's
 * {@link HouseholdContext}; every query is scoped by it. Writes require an active member of an active household
 * (BR-HH-10); archive readers only list.
 */
@Service
public class CategoryService {

    /** Unique index enforcing BR-CAT-02 (database-schema.md §6.1). */
    static final String UNIQUE_NAME = "uq_category_name";

    private final CurrentHousehold currentHousehold;
    private final CategoryRepository categories;
    private final SortOrderAllocator sortOrders;
    private final CursorCodec cursorCodec;
    private final Clock clock;

    CategoryService(CurrentHousehold currentHousehold, CategoryRepository categories,
                    SortOrderAllocator sortOrders, CursorCodec cursorCodec, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.categories = categories;
        this.sortOrders = sortOrders;
        this.cursorCodec = cursorCodec;
        this.clock = clock;
    }

    /** System categories followed by the household's own, archived ones only on request (BR-CAT-03). */
    @Transactional(readOnly = true)
    public CursorPage<Category> list(boolean includeArchived, PageQuery page) {
        HouseholdContext context = currentHousehold.currentHousehold();
        String scope = context.userId() + "|" + context.householdId() + "|categories|" + includeArchived;
        int afterGroup = -1;
        int afterSortOrder = -1;
        if (page.cursor().isPresent()) {
            List<String> position = cursorCodec.decode(scope, page.cursor().get());
            afterGroup = Integer.parseInt(position.get(0));
            afterSortOrder = Integer.parseInt(position.get(1));
        }
        List<Category> fetched = categories.findPage(context.householdId().value(), includeArchived, afterGroup,
                afterSortOrder, page.fetchSize());
        return CursorPage.of(fetched, page,
                category -> List.of(category.isSystem() ? "0" : "1", Integer.toString(category.sortOrder())),
                cursorCodec, scope);
    }

    /** BR-CAT-01, BR-CAT-02: creates a custom category appended after the household's last one. */
    @Transactional
    public Category create(String name) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable();
        String validName = Category.validName(name);
        if (categories.nameExists(context.householdId().value(), validName)) {
            throw nameAlreadyExists();
        }
        Instant now = clock.instant();
        Category category = Category.createCustom(UuidV7.generate(clock), context.householdId(), validName,
                sortOrders.next(context.householdId()), context.userId(), now);
        return flush(category);
    }

    /**
     * BR-CAT-02, BR-CAT-03: renames, archives or unarchives a custom category. A category that is not visible to
     * the caller's household is a 404 before the {@code If-Match} header is looked at; a stale version is a 412.
     */
    @Transactional
    public Category update(UUID id, @Nullable String name, @Nullable Boolean archived, @Nullable String ifMatch) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable();
        Category category = categories.findVisibleByIdAndHouseholdId(id, context.householdId().value())
                .orElseThrow(() -> new ApplicationException(CommonErrorCode.RESOURCE_NOT_FOUND,
                        "The category was not found."));
        long expectedVersion = VersionETag.requireIfMatch(ifMatch);
        category.requireCustom();
        VersionETag.requireMatch(expectedVersion, category.version());

        Instant now = clock.instant();
        if (name != null) {
            String validName = Category.validName(name);
            if (!validName.equals(category.name())) {
                if (categories.nameExistsExcluding(context.householdId().value(), validName, category.id())) {
                    throw nameAlreadyExists();
                }
                category.rename(validName, context.userId(), now);
            }
        }
        if (archived != null) {
            if (archived) {
                category.archive(context.userId(), now);
            } else {
                category.unarchive(context.userId(), now);
            }
        }
        return flush(category);
    }

    private Category flush(Category category) {
        try {
            return categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request took the name between the check and the write (BR-CAT-02).
            if (violates(e, UNIQUE_NAME)) {
                throw nameAlreadyExists();
            }
            throw e;
        }
    }

    private static ApplicationException nameAlreadyExists() {
        return new ApplicationException(CategoryErrorCode.CATEGORY_NAME_ALREADY_EXISTS,
                "A category with this name already exists in the household.");
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(Objects.toString(violation.getConstraintName(), ""))) {
                return true;
            }
        }
        return false;
    }
}
