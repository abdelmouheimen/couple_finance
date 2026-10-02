package com.couplefinance.budget.domain;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Persistence of budgets. Deliberately has no generic finder: every read of household data is scoped by household,
 * never {@code findById}.
 */
public interface BudgetRepository extends Repository<Budget, UUID> {

    Budget saveAndFlush(Budget budget);

    /** The budget of a period of the household, empty when there is none (BR-BUD-01). */
    @Query("select b from Budget b where b.householdId = :householdId and b.periodStart = :periodStart")
    Optional<Budget> findByPeriod(@Param("householdId") UUID householdId,
            @Param("periodStart") LocalDate periodStart);

    /** Same as {@link #findByPeriod} and locks the row {@code FOR UPDATE} until the end of the transaction. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Budget b where b.householdId = :householdId and b.periodStart = :periodStart")
    Optional<Budget> lockByPeriod(@Param("householdId") UUID householdId,
            @Param("periodStart") LocalDate periodStart);
}
