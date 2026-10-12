package com.couplefinance.identity.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Published when a user withdraws an AI consent (BR-AI-05: effective immediately, including for queued jobs).
 * Listeners must re-read current state rather than trust this payload.
 */
public record AiConsentWithdrawn(UUID userId, AiConsentPurpose purpose, Instant withdrawnAt) {
}
