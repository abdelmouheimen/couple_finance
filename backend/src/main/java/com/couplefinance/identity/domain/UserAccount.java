package com.couplefinance.identity.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * Read-only view of a user account, used to resolve the authenticated user. Accounts are written by
 * registration, which is not implemented yet; only the columns needed for authentication and the profile are mapped.
 */
@Entity
@Immutable
@Table(schema = "identity", name = "user_account")
public class UserAccount {

    @Id
    private UUID id;

    private String email;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "display_name")
    private String displayName;

    @Column(nullable = false)
    private String locale;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private UserAccountStatus status;

    protected UserAccount() {
        // for JPA
    }

    public UUID id() {
        return id;
    }

    public String email() {
        return email;
    }

    /** Encoded (Argon2id) password; {@code null} only for deleted accounts. Never log or serialise it. */
    public String passwordHash() {
        return passwordHash;
    }

    public String displayName() {
        return displayName;
    }

    public String locale() {
        return locale;
    }

    public UserAccountStatus status() {
        return status;
    }
}
