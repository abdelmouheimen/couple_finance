package com.couplefinance.identity.application;

import java.util.UUID;

import com.couplefinance.identity.domain.UserAccountStatus;

/** The caller's own identity, as exposed by {@code GET /api/v1/me}. */
public record CurrentUserProfile(UUID id, String email, String displayName, String locale,
                                 UserAccountStatus status) {
}
