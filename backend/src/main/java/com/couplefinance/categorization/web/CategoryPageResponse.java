package com.couplefinance.categorization.web;

import java.util.List;

import com.couplefinance.categorization.domain.Category;
import com.couplefinance.shared.pagination.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "CategoryPage", requiredProperties = {"items"})
record CategoryPageResponse(
        List<CategoryResponse> items,
        @Schema(description = "Opaque cursor of the next page, absent on the last page.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable String nextCursor) {

    static CategoryPageResponse from(CursorPage<Category> page) {
        return new CategoryPageResponse(page.items().stream().map(CategoryResponse::from).toList(),
                page.nextCursor());
    }
}
