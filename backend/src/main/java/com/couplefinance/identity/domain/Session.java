package com.couplefinance.identity.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A login session; its id is the {@code sid} claim of the access tokens (database-schema.md §4.2). */
@Entity
@Table(schema = "identity", name = "session")
public class Session {

    public static final int DEVICE_LABEL_MAX_LENGTH = 80;

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "device_label", updatable = false)
    private String deviceLabel;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_used_at", nullable = false)
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason")
    private String revokeReason;

    protected Session() {
        // for JPA
    }

    public Session(UUID id, UUID userId, String deviceLabel, Instant now) {
        if (deviceLabel != null && (deviceLabel.isBlank() || deviceLabel.length() > DEVICE_LABEL_MAX_LENGTH)) {
            throw new IllegalArgumentException("A device label has 1 to " + DEVICE_LABEL_MAX_LENGTH + " characters.");
        }
        this.id = id;
        this.userId = userId;
        this.deviceLabel = deviceLabel;
        this.createdAt = now;
        this.lastUsedAt = now;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }
}
