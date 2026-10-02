package com.couplefinance.platform.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A household created through the public API: two members, an invitation of the owner, a custom category, a SHARED
 * expense, a PERSONAL expense of the owner (with recognisable private text) and a PERSONAL merchant rule of the owner.
 */
public record SeededHousehold(UUID household, UUID owner, UUID partner, UUID invitation, UUID customCategory,
        UUID sharedExpense, UUID personalExpense, String personalMerchant, String personalNote, String ruleMerchant) {

    public static final String SYSTEM_GROCERIES = "019a0000-0000-7000-8000-000000000001";
    public static final String SHARED_AMOUNT = "12.50";

    /** Texts that identify the owner's private data and must never reach the partner or another household. */
    public List<String> privateMarkers() {
        return List.of(personalExpense.toString(), personalMerchant, personalNote, ruleMerchant);
    }

    /** Identifiers of the household's data that another household must never see. */
    public List<String> householdMarkers() {
        List<String> markers = new ArrayList<>(privateMarkers());
        markers.add(invitation.toString());
        markers.add(sharedExpense.toString());
        markers.add(customCategory.toString());
        markers.add(household.toString());
        return markers;
    }
}
