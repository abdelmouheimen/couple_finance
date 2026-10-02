package com.couplefinance.categorization.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Persistence of merchant rules and correction streaks. Every method is scoped by household and by owner
 * ({@code null} = household level); there is no unscoped access (security.md section 4.1).
 */
public interface MerchantRuleStore {

    /** Serialises concurrent learning/writes of one (household, owner, merchant) scope until commit. */
    void lockScope(UUID householdId, @Nullable UUID ownerUserId, String merchantKey);

    /** Category of the rule of exactly this scope, unless that category is archived (BR-CAT-04). */
    Optional<UUID> findActiveRuleCategory(UUID householdId, @Nullable UUID ownerUserId, String merchantKey);

    /** Creates or updates the single rule of the scope (BR-CAT-05). */
    void upsertRule(UUID ruleId, UUID householdId, @Nullable UUID ownerUserId, String merchantKey,
                    int normaliserVersion, UUID categoryId, Instant now);

    Optional<CorrectionStreak> findStreak(UUID householdId, @Nullable UUID ownerUserId, String merchantKey);

    void saveStreak(UUID rowId, UUID householdId, @Nullable UUID ownerUserId, String merchantKey,
                    CorrectionStreak streak, Instant now);

    void deleteStreak(UUID householdId, @Nullable UUID ownerUserId, String merchantKey);
}
