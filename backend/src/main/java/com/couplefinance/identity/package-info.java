/**
 * Identity module: user accounts, authentication (JWT validation) and the current user.
 *
 * <p>Current scope: validation and issuance of access tokens, email + password login with session and refresh token
 * creation (Issue #75), the current-user identity, and resolution of the authenticated, active user.
 * Registration, refresh/logout and email verification are not implemented yet (the in-house identity model was
 * decided by the Tech Lead).
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = "shared")
package com.couplefinance.identity;

import org.springframework.modulith.ApplicationModule;
