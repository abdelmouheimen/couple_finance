package com.couplefinance.identity.api;

import com.couplefinance.shared.id.UserId;

/**
 * The authenticated user of the current request. The identity always comes from the validated access token,
 * never from request parameters or bodies (CLAUDE.md §6).
 */
public interface CurrentUser {

    /**
     * Returns the id of the authenticated user, who must have an {@code ACTIVE} account (email verified).
     *
     * @throws org.springframework.security.core.AuthenticationException (401) if the request is not authenticated
     *         or the token does not designate an existing, non-deleted account
     * @throws com.couplefinance.shared.error.ApplicationException {@code EMAIL_NOT_VERIFIED} (403) if the account
     *         is not active yet
     */
    UserId requireActiveUser();
}
