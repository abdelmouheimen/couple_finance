package com.couplefinance.expense.domain;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Persistence of expenses. Deliberately has no generic finder: every read of household data must be scoped by
 * household and by visibility (BR-EXP-07), never {@code findById}.
 */
public interface ExpenseRepository extends Repository<Expense, UUID> {

    Expense saveAndFlush(Expense expense);

    /**
     * Loads a live expense of the household that {@code userId} may see (SHARED, or PERSONAL and owned by them) and
     * locks its row {@code FOR UPDATE} until the end of the transaction (domain-model.md section 7.3). Unknown,
     * deleted, foreign-household and partner-personal expenses are indistinguishable: all are empty.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select e from Expense e
            where e.id = :id and e.householdId = :householdId and e.deletedAt is null
              and (e.ownerUserId is null or e.ownerUserId = :userId)
            """)
    Optional<Expense> lockVisibleLiveById(@Param("id") UUID id, @Param("householdId") UUID householdId,
            @Param("userId") UUID userId);

    /** Sum, in minor units, of the live (non-deleted) refunds linked to an expense (BR-EXP-03). */
    @Query("""
            select coalesce(sum(r.amountMinor), 0) from Expense r
            where r.refundOfExpenseId = :originalId and r.householdId = :householdId and r.deletedAt is null
            """)
    long sumLiveRefundsMinor(@Param("originalId") UUID originalId, @Param("householdId") UUID householdId);

    /** Whether an expense has live refunds: it then cannot be deleted (BR-EXP-03, enforced by the delete use case). */
    @Query("""
            select count(r) > 0 from Expense r
            where r.refundOfExpenseId = :originalId and r.householdId = :householdId and r.deletedAt is null
            """)
    boolean hasLiveRefunds(@Param("originalId") UUID originalId, @Param("householdId") UUID householdId);
}
