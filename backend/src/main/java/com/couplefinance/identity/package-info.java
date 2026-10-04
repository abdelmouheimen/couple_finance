/**
 * Identity module: user accounts, authentication (JWT validation) and the current user.
 *
 * <p>Current scope: validation and issuance of access tokens, email + password login with session and refresh token
 * creation (Issue #75), the current-user identity, and resolution of the authenticated, active user.
 * Registration with email verification (Issue #77): accounts start {@code PENDING_VERIFICATION} and become
 * {@code ACTIVE} on single-use token consumption; emails go through the {@code EmailDelivery} port. Refresh/logout
 * and password reset are not implemented yet (the in-house identity model was decided by the Tech Lead).
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = "shared")
package com.couplefinance.identity;

import org.springframework.modulith.ApplicationModule;
