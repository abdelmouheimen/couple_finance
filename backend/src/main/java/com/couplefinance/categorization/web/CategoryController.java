package com.couplefinance.categorization.web;

import java.util.UUID;

import com.couplefinance.categorization.application.CategoryService;
import com.couplefinance.categorization.domain.Category;
import com.couplefinance.shared.concurrency.VersionETag;
import com.couplefinance.shared.error.ProblemSchema;
import com.couplefinance.shared.pagination.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Categories of the caller's household (BR-CAT-01..03). There is deliberately no DELETE endpoint (BR-CAT-03). */
@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories")
class CategoryController {

    private final CategoryService categories;

    CategoryController(CategoryService categories) {
        this.categories = categories;
    }

    @GetMapping
    @Operation(operationId = "listCategories", summary = "List categories",
            description = "System categories followed by the household's own, ordered by sort order. Archived "
                    + "categories are flagged and excluded unless includeArchived is true. Readable by archive "
                    + "readers of a dissolved household.")
    @ApiResponse(responseCode = "200", description = "A page of categories")
    @ApiResponse(responseCode = "400", description = "INVALID_LIMIT or INVALID_CURSOR",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    CategoryPageResponse list(
            @Parameter(description = "Include archived categories (flagged archived = true).")
            @RequestParam(defaultValue = "false") boolean includeArchived,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {
        return CategoryPageResponse.from(categories.list(includeArchived, PageQuery.of(limit, cursor)));
    }

    @PostMapping
    @Operation(operationId = "createCategory", summary = "Create a custom category",
            description = "Creates an active custom category in the caller's household. The ETag header carries "
                    + "its version.")
    @ApiResponse(responseCode = "201", description = "Category created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or MALFORMED_REQUEST",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "CATEGORY_NAME_ALREADY_EXISTS or CATEGORY_LIMIT_REACHED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<CategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        return respond(HttpStatus.CREATED, categories.create(request.name()));
    }

    @PatchMapping("/{id}")
    @Operation(operationId = "updateCategory", summary = "Rename, archive or unarchive a custom category",
            description = "Requires If-Match with the category version (ETag). System categories cannot be "
                    + "modified. Categories are never deleted: archiving is the delete.")
    @ApiResponse(responseCode = "200", description = "Category updated (or unchanged)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST or IF_MATCH_INVALID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED, HOUSEHOLD_READ_ONLY or "
            + "SYSTEM_CATEGORY_IMMUTABLE", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or RESOURCE_NOT_FOUND (also for a "
            + "category of another household)", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "CATEGORY_NAME_ALREADY_EXISTS",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "412", description = "VERSION_CONFLICT",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "428", description = "IF_MATCH_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<CategoryResponse> update(@PathVariable UUID id,
            @Parameter(description = "Version of the category last read, as sent in its ETag.")
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateCategoryRequest request) {
        return respond(HttpStatus.OK, categories.update(id, request.name(), request.archived(), ifMatch));
    }

    private static ResponseEntity<CategoryResponse> respond(HttpStatus status, Category category) {
        return ResponseEntity.status(status).eTag(VersionETag.render(category.version()))
                .body(CategoryResponse.from(category));
    }
}
