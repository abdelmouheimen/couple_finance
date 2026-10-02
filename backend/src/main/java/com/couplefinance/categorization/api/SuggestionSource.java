package com.couplefinance.categorization.api;

/** Where a category suggestion comes from (BR-CAT-04); shown to the user. The AI step is not implemented yet. */
public enum SuggestionSource {
    USER_RULE, HOUSEHOLD_RULE, DEFAULT_OTHER
}
