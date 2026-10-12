package com.couplefinance.identity.api;

import com.couplefinance.shared.id.UserId;

/**
 * Current AI consent of a user (ADR-005 §8): read by the calling business module before any AI call and re-asserted
 * by the guard of {@code ai.infrastructure} just before the provider call. Consent is never inferred: without an
 * explicit, not-withdrawn grant the answer is {@code false}.
 */
public interface AiConsents {

    boolean isGranted(UserId user, AiConsentPurpose purpose);
}
