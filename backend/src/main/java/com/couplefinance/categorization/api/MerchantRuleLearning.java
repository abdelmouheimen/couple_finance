package com.couplefinance.categorization.api;

/** Implicit rule learning from saved expenses (BR-CAT-05). */
public interface MerchantRuleLearning {

    /**
     * Records a saved expense: two consecutive saves of the same merchant with the same category that differs from
     * the current suggestion create or update the rule of the matching scope. Idempotent per expense id: handling
     * the same save twice changes nothing. Ignores an empty merchant and an unusable (unknown or archived) category.
     */
    void learn(LearnedSave save);
}
