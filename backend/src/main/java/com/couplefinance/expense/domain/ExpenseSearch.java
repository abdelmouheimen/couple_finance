package com.couplefinance.expense.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Read port of the expense history (F9). Every query is scoped by household and by view: the household view
 * selects SHARED expenses only, the personal view only the caller's own PERSONAL ones (BR-EXP-07), so a partner's
 * PERSONAL expense can never match, be counted or be totalled. Deleted expenses are excluded (BR-EXP-11).
 */
public interface ExpenseSearch {

    /**
     * @param scope  {@code SHARED}: household view; {@code PERSONAL}: personal view of {@code userId}
     * @param userId the authenticated caller (owner of the personal view)
     * @param text   case-insensitive substring of merchant or note, already trimmed
     */
    record Criteria(UUID householdId, SharingType scope, UUID userId, @Nullable LocalDate from,
            @Nullable LocalDate to, @Nullable UUID categoryId, @Nullable UUID paidByUserId,
            @Nullable ExpenseKind kind, @Nullable Boolean hasReceipt, @Nullable String text) {}

    /** Sort position of an expense: date then id, both descending. */
    record Position(LocalDate date, UUID id) {}

    /**
     * Server-computed totals of everything matching the filters (not only the current page): {@code count} of
     * matching expenses and {@code netMinor} = EXPENSE - REFUND, TRANSFER excluded (BR-SCP-01, BR-SCP-02). With a
     * category filter only the items of that category are summed (BR-BUD-03).
     */
    record Totals(long count, long netMinor) {}

    /** Positions of up to {@code limit} expenses after {@code after}, newest first. */
    List<Position> page(Criteria criteria, Optional<Position> after, int limit);

    Totals totals(Criteria criteria);
}
