package com.couplefinance.expense.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read port of the spending aggregates (BR-SCP-01/02). Every query is scoped by household and by view: SHARED
 * ({@code owner_user_id IS NULL}) or the caller's own PERSONAL expenses, so a partner's PERSONAL expense can never
 * be summed. Only EXPENSE adds and REFUND subtracts; TRANSFER and deleted expenses are excluded. Amounts are
 * signed minor units.
 */
public interface SpendingAggregates {

    /** @param userId the authenticated caller (owner of the personal view) */
    record Criteria(UUID householdId, SharingType scope, UUID userId, LocalDate start, LocalDate end) {}

    record Entry<K>(K key, long netMinor) {}

    long total(Criteria criteria);

    List<Entry<UUID>> byCategory(Criteria criteria);

    List<Entry<UUID>> byPayer(Criteria criteria);

    List<Entry<LocalDate>> byDay(Criteria criteria);
}
