package com.couplefinance.expense.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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

    /**
     * Reads, without locking, a live expense of the household that {@code userId} may see (SHARED, or PERSONAL and
     * owned by them). Unknown, deleted, foreign-household and partner-personal expenses are indistinguishable: all
     * are empty (BR-EXP-07).
     */
    @Query("""
            select e from Expense e
            where e.id = :id and e.householdId = :householdId and e.deletedAt is null
              and (e.ownerUserId is null or e.ownerUserId = :userId)
            """)
    Optional<Expense> findVisibleLiveById(@Param("id") UUID id, @Param("householdId") UUID householdId,
            @Param("userId") UUID userId);

    /**
     * Re-reads a live expense for a background consumer of its events: scoped by the household of the event, with
     * no user visibility filter (the consumer applies the expense's own owner, BR-EXP-07, and must never relay a
     * PERSONAL expense to the partner). Foreign-household and deleted expenses are empty.
     */
    @Query("""
            select e from Expense e where e.id = :id and e.householdId = :householdId and e.deletedAt is null
            """)
    Optional<Expense> findLiveByIdAndHouseholdId(@Param("id") UUID id, @Param("householdId") UUID householdId);

    /** Sum, in minor units, of the live (non-deleted) refunds linked to an expense (BR-EXP-03). */
    @Query("""
            select coalesce(sum(r.amountMinor), 0) from Expense r
            where r.refundOfExpenseId = :originalId and r.householdId = :householdId and r.deletedAt is null
            """)
    long sumLiveRefundsMinor(@Param("originalId") UUID originalId, @Param("householdId") UUID householdId);

    /** Date of the earliest live refund linked to an expense, empty when it has none (BR-EXP-03). */
    @Query("""
            select min(r.expenseDate) from Expense r
            where r.refundOfExpenseId = :originalId and r.householdId = :householdId and r.deletedAt is null
            """)
    Optional<LocalDate> earliestLiveRefundDate(@Param("originalId") UUID originalId,
            @Param("householdId") UUID householdId);

    /** Whether an expense has live refunds: it then cannot be deleted (BR-EXP-03, enforced by the delete use case). */
    @Query("""
            select count(r) > 0 from Expense r
            where r.refundOfExpenseId = :originalId and r.householdId = :householdId and r.deletedAt is null
            """)
    boolean hasLiveRefunds(@Param("originalId") UUID originalId, @Param("householdId") UUID householdId);

    /**
     * Loads, with their items, the live expenses of the household with the given ids. The ids come from
     * {@link ExpenseSearch}, which already applied the visibility rule (BR-EXP-07); the household scope is repeated
     * here as defence in depth. Order is unspecified: the caller restores the page order.
     */
    @Query("""
            select distinct e from Expense e left join fetch e.items
            where e.id in :ids and e.householdId = :householdId and e.deletedAt is null
              and (e.ownerUserId is null or e.ownerUserId = :userId)
            """)
    List<Expense> findVisibleLiveByIds(@Param("ids") Collection<UUID> ids, @Param("householdId") UUID householdId,
            @Param("userId") UUID userId);
}
