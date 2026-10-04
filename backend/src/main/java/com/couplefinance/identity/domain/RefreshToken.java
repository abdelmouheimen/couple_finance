package com.couplefinance.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** An opaque refresh token; only its SHA-256 hash is persisted (database-schema.md §4.3). */
@Entity
@Table(schema = "identity", name = "refresh_token")
public class RefreshToken {

    public static final int HASH_LENGTH = 32;

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "replaced_by_id")
    private UUID replacedById;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    protected RefreshToken() {
        // for JPA
    }

    public RefreshToken(UUID id, UUID sessionId, byte[] tokenHash, Instant issuedAt, Instant expiresAt) {
        if (tokenHash.length != HASH_LENGTH) {
            throw new IllegalArgumentException("A refresh token hash is a SHA-256 digest.");
        }
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("A refresh token must expire after its issuance.");
        }
        this.id = id;
        this.sessionId = sessionId;
        this.tokenHash = tokenHash.clone();
        this.issuedAt = issuedAt;
        this.expiresAt = expiresAt;
    }

    public UUID id() {
        return id;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public UUID sessionId() {
        return sessionId;
    }

    public Instant rotatedAt() {
        return rotatedAt;
    }

    public UUID replacedById() {
        return replacedById;
    }

    public boolean isRotated() {
        return rotatedAt != null;
    }

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Marks this token as replaced by {@code successorId}; only the newest token of a chain can be rotated. */
    public void rotateTo(UUID successorId, Instant now) {
        if (rotatedAt != null) {
            throw new IllegalStateException("Only the newest token of a chain can be rotated.");
        }
        this.rotatedAt = now;
        this.replacedById = successorId;
    }
}
