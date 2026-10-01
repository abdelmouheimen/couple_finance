package com.couplefinance.shared.error;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;

/**
 * OpenAPI description of the error body produced by {@link GlobalExceptionHandler}. Documentation only: Spring's
 * {@code ProblemDetail} serializes its extra properties ({@code code}, {@code errors}) as top-level fields, which
 * its own generated schema does not show. Reference it in {@code @ApiResponse} content.
 */
@Schema(name = "Problem", description = "RFC 9457 Problem Details with a stable `code`.")
public record ProblemSchema(
        @Schema(example = "about:blank") String type,
        @Schema(requiredMode = RequiredMode.REQUIRED, example = "Bad Request") String title,
        @Schema(requiredMode = RequiredMode.REQUIRED, example = "400") int status,
        @Schema(example = "The request contains invalid values.") String detail,
        @Schema(example = "/api/v1/households") String instance,
        @Schema(requiredMode = RequiredMode.REQUIRED, description = "Stable machine-readable error code.",
                example = "VALIDATION_FAILED") String code,
        @Schema(description = "Invalid fields; present for VALIDATION_FAILED.")
        List<GlobalExceptionHandler.FieldViolation> errors) {
}
