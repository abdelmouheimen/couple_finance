package com.couplefinance.budget.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate root of the household's spending limits for one budget period (domain-model.md section 8). Holds
 * limits only: consumption is computed from expenses, never stored (BR-BUD-03).
 *
 * <p>Current scope (Issue #24): the overall limit. The invariants are enforced here so that no code path builds
 * an invalid budget: BR-BUD-01 one budget per household and period (also a database unique constraint),
 * BR-BUD-02 at least one limit, BR-MON-02/03/07 strictly positive exact amount in the household currency. The
 * "at least one limit" rule is "overall limit present OR at least one category limit"; category limits arrive
 * with Issue #25, which extends {@link #requireAtLeastOneLimit}.
 */
@Entity
@Table(schema = "budget", name = "budget")
public class Budget {

    /** Database check {@code ck_budget_overall_limit}: a limit is at most 10^15 minor units. */
    public static final long MAX_LIMIT_MINOR = 1_000_000_000_000_000L;

    @Id
    private UUID id;

    private UUID householdId;

    private LocalDate periodStart;

    private LocalDate periodEnd;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    /** {@code null} when the budget has no overall limit (only possible once category limits exist). */
    private Long overallLimitMinor;

    private Instant createdAt;

    private UUID createdBy;

    private Instant updatedAt;

    private UUID updatedBy;

    @Version
    private Long version;

    protected Budget() {
        // for JPA
    }

    /**
     * Creates the budget of {@code period} with an overall limit (BR-BUD-01, BR-BUD-02).
     *
     * @param period       an existing period of the household calendar (BR-HH-07)
     * @param decimals     minor-unit decimals of the household currency
     * @param overallLimit {@code null} is rejected: a budget needs at least one limit and no category limit exists
     *                     yet
     * @throws ApplicationException the first violated rule, with a stable error code
     */
    public static Budget create(HouseholdId household, BudgetPeriod period, CurrencyCode currency, int decimals,
            Clock clock, @Nullable Money overallLimit, UserId creator) {
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(period, "period");
        Objects.requireNonNull(creator, "creator");
        Money limit = validLimit(overallLimit, currency, decimals);

        Instant now = clock.instant();
        Budget budget = new Budget();
        budget.id = UuidV7.generate(clock);
        budget.householdId = household.value();
        budget.periodStart = period.start();
        budget.periodEnd = period.end();
        budget.currency = currency.value();
        budget.overallLimitMinor = limit.minorUnits();
        budget.createdAt = now;
        budget.createdBy = creator.value();
        budget.updatedAt = now;
        budget.updatedBy = creator.value();
        budget.requireAtLeastOneLimit(0);
        return budget;
    }

    /**
     * Sets the overall limit (BR-BUD-07: allowed on past periods too). Nothing changes, and {@code false} is
     * returned, when the limit is already the given one.
     *
     * @throws ApplicationException the first violated rule, with a stable error code
     */
    public boolean setOverallLimit(@Nullable Money overallLimit, int decimals, Clock clock, UserId editor) {
        Objects.requireNonNull(editor, "editor");
        Money limit = validLimit(overallLimit, currency(), decimals);
        if (overallLimitMinor != null && overallLimitMinor == limit.minorUnits()) {
            return false;
        }
        this.overallLimitMinor = limit.minorUnits();
        this.updatedAt = clock.instant();
        this.updatedBy = editor.value();
        requireAtLeastOneLimit(0);
        return true;
    }

    /**
     * Validates a requested overall limit without any state: present (BR-BUD-02, as no category limit exists
     * yet), in {@code currency} (BR-MON-07), strictly positive and at most the storable maximum.
     */
    public static Money validLimit(@Nullable Money overallLimit, CurrencyCode currency, int decimals) {
        if (overallLimit == null) {
            throw new ApplicationException(BudgetErrorCode.BUDGET_LIMIT_REQUIRED,
                    "A budget needs at least one limit: overallLimit is required.");
        }
        overallLimit.requirePositiveAtMost(Money.ofMinor(MAX_LIMIT_MINOR, currency, decimals));
        return overallLimit;
    }

    /**
     * BR-BUD-02: overall limit present OR at least one category limit. Category limits do not exist yet
     * (Issue #25), so the caller passes their count, 0 for now.
     */
    private void requireAtLeastOneLimit(int categoryLimitCount) {
        if (overallLimitMinor == null && categoryLimitCount == 0) {
            throw new ApplicationException(BudgetErrorCode.BUDGET_LIMIT_REQUIRED,
                    "A budget needs at least one limit.");
        }
    }

    public UUID id() {
        return id;
    }

    public UUID householdId() {
        return householdId;
    }

    public LocalDate periodStart() {
        return periodStart;
    }

    public LocalDate periodEnd() {
        return periodEnd;
    }

    public CurrencyCode currency() {
        return new CurrencyCode(currency);
    }

    public @Nullable Long overallLimitMinor() {
        return overallLimitMinor;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public long version() {
        return version == null ? 0L : version;
    }
}
