package com.couplefinance.shared.pagination;

import java.util.List;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * One page of a cursor-paginated listing. {@code nextCursor} is {@code null} on the last page.
 *
 * <p>Usage: query with a stable total order (sort columns plus a unique tie-breaker such as the id) and
 * {@link PageQuery#fetchSize()} rows, starting after the position decoded from the request cursor, then call
 * {@link #of}.
 */
public record CursorPage<T>(List<T> items, @Nullable String nextCursor) {

    public CursorPage {
        items = List.copyOf(items);
    }

    /**
     * @param fetched up to {@code query.fetchSize()} rows in listing order
     * @param positionOf the sort position (values of the ordering columns, tie-breaker last) of an item
     * @param scope who and what this cursor is valid for, see {@link CursorCodec#encode}
     */
    public static <T> CursorPage<T> of(List<T> fetched, PageQuery query, Function<T, List<String>> positionOf,
            CursorCodec codec, String scope) {
        if (fetched.size() <= query.limit()) {
            return new CursorPage<>(fetched, null);
        }
        List<T> items = fetched.subList(0, query.limit());
        return new CursorPage<>(items, codec.encode(scope, positionOf.apply(items.get(items.size() - 1))));
    }
}
