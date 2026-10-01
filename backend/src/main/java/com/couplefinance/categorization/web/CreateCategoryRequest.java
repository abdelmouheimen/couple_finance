package com.couplefinance.categorization.web;

import com.couplefinance.categorization.domain.Category;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Body of {@code POST /api/v1/categories}. Icon, colour and order are assigned by the server. */
@Schema(name = "CreateCategoryRequest")
record CreateCategoryRequest(
        @Schema(description = "Category name, trimmed, 1 to " + Category.NAME_MAX_LENGTH
                + " characters; unique in the household, case-insensitively.", example = "Pets")
        @NotBlank String name) {
}
