package com.couplefinance.identity.domain;

import java.time.Instant;
import java.util.Optional;

import com.couplefinance.identity.api.AiConsentPurpose;
import com.couplefinance.shared.id.UserId;

/** Append-only consent evidence (database-schema.md section 4.5). */
public interface AiConsentLog {

    record Decision(boolean granted, String textVersion, Instant at) {
    }

    Optional<Decision> latest(UserId user, AiConsentPurpose purpose);

    void append(UserId user, AiConsentPurpose purpose, boolean granted, String textVersion, Instant at);
}
