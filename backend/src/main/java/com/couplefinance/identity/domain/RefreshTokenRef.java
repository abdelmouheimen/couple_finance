package com.couplefinance.identity.domain;

import java.util.UUID;

/** Identifiers of the token matching a presented secret; read without locking, state is re-read under the lock. */
public record RefreshTokenRef(UUID id, UUID sessionId) {
}
