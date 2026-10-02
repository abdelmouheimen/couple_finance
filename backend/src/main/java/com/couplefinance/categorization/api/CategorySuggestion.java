package com.couplefinance.categorization.api;

import java.util.Objects;
import java.util.UUID;

/** A suggested category and its source. It stays a suggestion until the expense is saved (BR-CAT-04). */
public record CategorySuggestion(UUID categoryId, SuggestionSource source) {

    public CategorySuggestion {
        Objects.requireNonNull(categoryId, "categoryId");
        Objects.requireNonNull(source, "source");
    }
}
