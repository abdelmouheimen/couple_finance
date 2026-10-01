package com.couplefinance.categorization.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import org.jspecify.annotations.Nullable;

/** Body of {@code PATCH /api/v1/categories/{id}}: omitted fields are left unchanged. At least one is required. */
@Schema(name = "UpdateCategoryRequest")
record UpdateCategoryRequest(
        @Schema(description = "New name of a custom category, trimmed, 1 to 40 characters.", example = "Pets",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable String name,

        @Schema(description = "true archives, false unarchives a custom category.",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable Boolean archived) {

    @Schema(hidden = true)
    @AssertTrue(message = "name or archived is required")
    boolean isChangeRequested() {
        return name != null || archived != null;
    }
}
