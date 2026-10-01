package com.couplefinance.categorization.web;

import java.time.Instant;
import java.util.UUID;

import com.couplefinance.categorization.domain.Category;
import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.Nullable;

@Schema(name = "Category", requiredProperties = {
        "id", "type", "icon", "color", "sortOrder", "archived", "version"})
record CategoryResponse(
        UUID id,
        @Schema(allowableValues = {"SYSTEM", "CUSTOM"}, example = "CUSTOM") String type,
        @Schema(description = "Code of a system category; the client displays it translated. Absent on custom "
                + "categories.", example = "GROCERIES", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable String systemCode,
        @Schema(description = "Name of a custom category. Absent on system categories.", example = "Pets",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        @Nullable String name,
        @Schema(example = "tag") String icon,
        @Schema(example = "#9E9E9E") String color,
        int sortOrder,
        boolean archived,
        @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable Instant archivedAt,
        @Schema(description = "Version, also sent as ETag; send it back in If-Match to update.") long version) {

    static CategoryResponse from(Category category) {
        return new CategoryResponse(category.id(), category.isSystem() ? "SYSTEM" : "CUSTOM", category.systemCode(),
                category.name(), category.icon(), category.color(), category.sortOrder(), category.isArchived(),
                category.archivedAt(), category.version());
    }
}
