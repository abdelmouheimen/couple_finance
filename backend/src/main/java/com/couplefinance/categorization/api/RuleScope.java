package com.couplefinance.categorization.api;

/**
 * Whether a merchant rule concerns a SHARED expense (household rules apply, BR-CAT-04) or a PERSONAL one (the
 * private user rule of the owner applies first, BR-CAT-05). Mirrors the sharing type of an expense without
 * depending on the expense module.
 */
public enum RuleScope {
    SHARED, PERSONAL
}
