package com.couplefinance.shared.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.Test;

class PageQueryTest {

    private final CursorCodec codec = new CursorCodec(new byte[32]);

    @Test
    void limit_defaults_when_absent() {
        assertThat(PageQuery.of(null, null).limit()).isEqualTo(PageQuery.DEFAULT_LIMIT);
    }

    @Test
    void limit_is_accepted_up_to_the_maximum_and_capped_above_it() {
        assertThat(PageQuery.of(1, null).limit()).isEqualTo(1);
        assertThat(PageQuery.of(100, null).limit()).isEqualTo(100);
        assertThat(PageQuery.of(101, null).limit()).isEqualTo(100);
        assertThat(PageQuery.of(Integer.MAX_VALUE, null).limit()).isEqualTo(100);
    }

    @Test
    void limit_below_one_is_rejected() {
        for (int limit : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThatThrownBy(() -> PageQuery.of(limit, null))
                    .isInstanceOfSatisfying(ApplicationException.class,
                            ex -> assertThat(ex.errorCode()).isEqualTo(PaginationErrorCode.INVALID_LIMIT));
        }
    }

    @Test
    void blank_cursor_means_first_page() {
        assertThat(PageQuery.of(5, " ").cursor()).isEmpty();
        assertThat(PageQuery.of(5, "abc").cursor()).contains("abc");
    }

    @Test
    void the_next_cursor_is_present_only_when_more_rows_exist() {
        PageQuery query = PageQuery.of(2, null);

        CursorPage<String> full = CursorPage.of(List.of("a", "b", "c"), query, List::of, codec, "s");
        CursorPage<String> last = CursorPage.of(List.of("c"), query, List::of, codec, "s");
        CursorPage<String> exact = CursorPage.of(List.of("a", "b"), query, List::of, codec, "s");

        assertThat(full.items()).containsExactly("a", "b");
        assertThat(full.nextCursor()).isNotNull();
        assertThat(codec.decode("s", full.nextCursor())).containsExactly("b");
        assertThat(last.nextCursor()).isNull();
        assertThat(exact.nextCursor()).isNull();
    }
}
