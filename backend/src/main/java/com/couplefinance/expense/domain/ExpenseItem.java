package com.couplefinance.expense.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/** One category line of an expense (BR-EXP-08). Owned by {@link Expense}; never loaded on its own. */
@Entity
@Table(schema = "expense", name = "expense_item")
public class ExpenseItem {

    public static final int LABEL_MAX_LENGTH = 60;

    @Id
    private UUID id;

    private UUID householdId;

    @Column(nullable = false)
    private short position;

    private UUID categoryId;

    private long amountMinor;

    private String label;

    protected ExpenseItem() {
        // for JPA
    }

    ExpenseItem(UUID id, UUID householdId, int position, UUID categoryId, long amountMinor, @Nullable String label) {
        this.id = id;
        this.householdId = householdId;
        this.position = (short) position;
        this.categoryId = categoryId;
        this.amountMinor = amountMinor;
        this.label = label;
    }

    public UUID id() {
        return id;
    }

    public int position() {
        return position;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public @Nullable String label() {
        return label;
    }
}
