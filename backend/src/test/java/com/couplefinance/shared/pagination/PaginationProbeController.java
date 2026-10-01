package com.couplefinance.shared.pagination;

import java.util.List;
import java.util.stream.IntStream;

import io.swagger.v3.oas.annotations.Hidden;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only listing exercising the pagination convention the way a real endpoint does: 250 ordered items per
 * caller ("subject-item-NNN"); the cursor scope is built from the authenticated principal.
 */
@Hidden
@RestController
@RequestMapping("/test-support/pagination")
class PaginationProbeController {

    private static final int ITEMS = 250;

    private final CursorCodec codec;

    PaginationProbeController(CursorCodec codec) {
        this.codec = codec;
    }

    @GetMapping
    CursorPage<String> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) Integer limit, @RequestParam(required = false) String cursor) {
        PageQuery query = PageQuery.of(limit, cursor);
        String scope = jwt.getSubject() + "|probe";
        int after = query.cursor().map(value -> Integer.parseInt(codec.decode(scope, value).get(0))).orElse(-1);
        List<Integer> fetched = IntStream.range(after + 1, ITEMS).limit(query.fetchSize()).boxed().toList();
        CursorPage<Integer> page = CursorPage.of(fetched, query, index -> List.of(Integer.toString(index)), codec, scope);
        return new CursorPage<>(
                page.items().stream().map(i -> jwt.getSubject() + "-item-" + String.format("%03d", i)).toList(),
                page.nextCursor());
    }
}
