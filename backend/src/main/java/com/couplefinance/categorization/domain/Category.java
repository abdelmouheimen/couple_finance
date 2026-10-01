package com.couplefinance.categorization.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.error.CommonErrorCode;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate root of the category vocabulary (domain-model.md §5). A system category ({@code householdId == null})
 * has a code and no name and is immutable (BR-CAT-03); a custom category belongs to one household, has a unique
 * name (BR-CAT-02) and can be renamed, archived and unarchived. Categories are never deleted (BR-CAT-03).
 */
@Entity
@Table(schema = "categorization", name = "category")
public class Category {

    public static final int NAME_MAX_LENGTH = 40;
    public static final String DEFAULT_ICON = "tag";
    public static final String DEFAULT_COLOR = "#9E9E9E";
    /** {@code sort_order} is a smallint. */
    public static final int MAX_SORT_ORDER = Short.MAX_VALUE;

    @Id
    private UUID id;

    private UUID householdId;

    private String systemCode;

    private String name;

    @Column(nullable = false)
    private String icon;

    @Column(nullable = false)
    private String color;

    @Column(nullable = false)
    private short sortOrder;

    private Instant archivedAt;

    private Instant createdAt;

    private UUID createdBy;

    private Instant updatedAt;

    private UUID updatedBy;

    @Version
    private Long version;

    protected Category() {
        // for JPA
    }

    /** Creates a custom, active category of the household (BR-CAT-01, BR-CAT-02). */
    public static Category createCustom(UUID id, HouseholdId household, String name, int sortOrder, UserId actor,
                                        Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        if (sortOrder < 0 || sortOrder > MAX_SORT_ORDER) {
            throw new ApplicationException(CategoryErrorCode.CATEGORY_LIMIT_REACHED,
                    "The household has reached the maximum number of categories.");
        }
        Category category = new Category();
        category.id = id;
        category.householdId = household.value();
        category.name = validName(name);
        category.icon = DEFAULT_ICON;
        category.color = DEFAULT_COLOR;
        category.sortOrder = (short) sortOrder;
        category.createdAt = now;
        category.createdBy = actor.value();
        category.updatedAt = now;
        category.updatedBy = actor.value();
        return category;
    }

    /** Trims and validates a custom category name: 1 to {@value #NAME_MAX_LENGTH} characters. */
    public static String validName(@Nullable String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new ApplicationException(CommonErrorCode.VALIDATION_FAILED,
                    "The category name must have 1 to " + NAME_MAX_LENGTH + " characters.");
        }
        return trimmed;
    }

    public void rename(String newName, UserId actor, Instant now) {
        requireCustom();
        this.name = validName(newName);
        touch(actor, now);
    }

    /** Idempotent: archiving an archived category changes nothing. */
    public void archive(UserId actor, Instant now) {
        requireCustom();
        if (archivedAt == null) {
            this.archivedAt = now;
            touch(actor, now);
        }
    }

    /** Idempotent: unarchiving an active category changes nothing. */
    public void unarchive(UserId actor, Instant now) {
        requireCustom();
        if (archivedAt != null) {
            this.archivedAt = null;
            touch(actor, now);
        }
    }

    /** BR-CAT-03: whatever the change requested, a system category is never modified. */
    public void requireCustom() {
        if (isSystem()) {
            throw new ApplicationException(CategoryErrorCode.SYSTEM_CATEGORY_IMMUTABLE,
                    "System categories cannot be renamed or archived.");
        }
    }

    private void touch(UserId actor, Instant now) {
        this.updatedAt = now;
        this.updatedBy = actor.value();
    }

    public boolean isSystem() {
        return householdId == null;
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public UUID id() {
        return id;
    }

    public @Nullable UUID householdId() {
        return householdId;
    }

    public @Nullable String systemCode() {
        return systemCode;
    }

    public @Nullable String name() {
        return name;
    }

    public String icon() {
        return icon;
    }

    public String color() {
        return color;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public @Nullable Instant archivedAt() {
        return archivedAt;
    }

    public long version() {
        return version == null ? 0 : version;
    }
}
