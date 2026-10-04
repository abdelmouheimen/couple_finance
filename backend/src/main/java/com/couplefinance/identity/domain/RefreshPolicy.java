package com.couplefinance.identity.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * Decision table of a refresh-token presentation (security.md §3, ADR-007 option A). Pure: the caller holds the
 * session row lock and passes the current state.
 */
public final class RefreshPolicy {

    /** Reuse grace window: a token rotated less than this long ago is a parallel refresh, not a replay. */
    public static final Duration GRACE_WINDOW = Duration.ofSeconds(30);

    /** What to do with a presented refresh token. */
    public enum Decision {
        /** The token is the newest of its chain: rotate it. */
        ROTATE,
        /** Already rotated, within the grace window: rotate the chain head forward (single chain). */
        ROTATE_FORWARD,
        /** Already rotated, outside the grace window: replay; revoke the session. */
        REUSE_DETECTED,
        /** Revoked or expired token, or revoked session: refuse. */
        REJECT
    }

    private RefreshPolicy() {
    }

    public static Decision decide(Session session, RefreshToken token, Instant now) {
        if (session.isRevoked() || token.isRevoked() || token.isExpired(now)) {
            return Decision.REJECT;
        }
        if (!token.isRotated()) {
            return Decision.ROTATE;
        }
        return now.isBefore(token.rotatedAt().plus(GRACE_WINDOW)) ? Decision.ROTATE_FORWARD : Decision.REUSE_DETECTED;
    }
}
