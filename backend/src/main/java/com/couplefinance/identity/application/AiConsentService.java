package com.couplefinance.identity.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.couplefinance.identity.api.AiConsentPurpose;
import com.couplefinance.identity.api.AiConsentWithdrawn;
import com.couplefinance.identity.api.CurrentUser;
import com.couplefinance.identity.domain.AiConsentLog;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UserId;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The caller's AI consents (ADR-005, BR-AI-05): explicit, per purpose, opt-in, withdrawable, recorded with the
 * wording version the user saw. A withdrawal publishes {@link AiConsentWithdrawn} so queued work stops.
 */
@Service
public class AiConsentService {

    public static final int TEXT_VERSION_MAX_LENGTH = 40;

    private final CurrentUser currentUser;
    private final AiConsentLog log;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    AiConsentService(CurrentUser currentUser, AiConsentLog log, ApplicationEventPublisher events, Clock clock) {
        this.currentUser = currentUser;
        this.log = log;
        this.events = events;
        this.clock = clock;
    }

    /** Current state of one purpose: granted or not, and the wording version of the last decision. */
    public record ConsentState(boolean granted, Optional<String> textVersion, Optional<Instant> decidedAt) {
    }

    @Transactional(readOnly = true)
    public Map<AiConsentPurpose, ConsentState> current() {
        UserId user = currentUser.requireActiveUser();
        Map<AiConsentPurpose, ConsentState> states = new EnumMap<>(AiConsentPurpose.class);
        for (AiConsentPurpose purpose : AiConsentPurpose.values()) {
            states.put(purpose, log.latest(user, purpose)
                    .map(d -> new ConsentState(d.granted(), Optional.of(d.textVersion()), Optional.of(d.at())))
                    .orElse(new ConsentState(false, Optional.empty(), Optional.empty())));
        }
        return states;
    }

    /** Records a decision; a decision equal to the current one is recorded again (it is evidence of re-display). */
    @Transactional
    public ConsentState decide(AiConsentPurpose purpose, boolean granted, String textVersion) {
        Objects.requireNonNull(purpose, "purpose");
        if (textVersion == null || textVersion.isBlank() || textVersion.length() > TEXT_VERSION_MAX_LENGTH) {
            throw new ApplicationException(IdentityErrorCode.AI_CONSENT_TEXT_VERSION_INVALID,
                    "The consent wording version is required (at most 40 characters).");
        }
        UserId user = currentUser.requireActiveUser();
        Instant now = clock.instant();
        boolean wasGranted = log.latest(user, purpose).map(AiConsentLog.Decision::granted).orElse(false);
        log.append(user, purpose, granted, textVersion.strip(), now);
        if (wasGranted && !granted) {
            events.publishEvent(new AiConsentWithdrawn(user.value(), purpose, now));
        }
        return new ConsentState(granted, Optional.of(textVersion.strip()), Optional.of(now));
    }
}
