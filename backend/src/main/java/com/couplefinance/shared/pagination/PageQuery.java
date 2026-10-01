package com.couplefinance.shared.pagination;

import java.util.Optional;

import com.couplefinance.shared.error.ApplicationException;
import org.jspecify.annotations.Nullable;

/**
 * Common pagination query parameters {@code cursor} and {@code limit} (architecture.md §7).
 *
 * <p>Behaviour of {@code limit}: absent → {@link #DEFAULT_LIMIT}; above {@link #MAX_LIMIT} → capped to
 * {@link #MAX_LIMIT} (not an error: the client still gets a valid page and a next cursor); below 1 → 400
 * {@code INVALID_LIMIT}. A non-integer value is rejected by request binding with 400. The cursor is opaque and
 * only valid for the caller and listing it was issued for (see {@link CursorCodec}).
 *
 * @param limit maximum number of items to return, between 1 and {@link #MAX_LIMIT}
 * @param cursor opaque cursor of the previous page, empty for the first page
 */
public record PageQuery(int limit, Optional<String> cursor) {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    public PageQuery {
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
    }

    /** Builds the query from raw request parameters, applying the documented limit rules. */
    public static PageQuery of(@Nullable Integer limit, @Nullable String cursor) {
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective < 1) {
            throw new ApplicationException(PaginationErrorCode.INVALID_LIMIT, "limit must be at least 1.");
        }
        return new PageQuery(Math.min(effective, MAX_LIMIT),
                cursor == null || cursor.isBlank() ? Optional.empty() : Optional.of(cursor));
    }

    /** Number of rows to fetch so that {@link CursorPage} can tell whether another page exists. */
    public int fetchSize() {
        return limit + 1;
    }
}
