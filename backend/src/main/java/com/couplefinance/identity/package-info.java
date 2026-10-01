/**
 * Identity module: user accounts, authentication (JWT validation) and the current user.
 *
 * <p>Current scope (Issue #1): validation of access tokens and resolution of the authenticated, active user.
 * Registration, login, sessions and token issuance are not implemented yet (security.md §3; in-house vs external
 * identity provider is still an open question).
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = "shared")
package com.couplefinance.identity;

import org.springframework.modulith.ApplicationModule;
