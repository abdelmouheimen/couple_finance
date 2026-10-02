package com.couplefinance.categorization.web;

import java.util.UUID;

import com.couplefinance.categorization.api.CategorySuggestion;
import com.couplefinance.categorization.api.SuggestionSource;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "CategorySuggestion", requiredProperties = {"categoryId", "source"})
record CategorySuggestionResponse(
        UUID categoryId,
        @Schema(description = "Where the suggestion comes from. It stays a suggestion until the expense is saved "
                + "(BR-CAT-04).") SuggestionSource source) {

    static CategorySuggestionResponse from(CategorySuggestion suggestion) {
        return new CategorySuggestionResponse(suggestion.categoryId(), suggestion.source());
    }
}
