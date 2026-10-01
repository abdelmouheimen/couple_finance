package com.couplefinance.identity.application;

import java.util.UUID;

import com.couplefinance.identity.api.CurrentUser;
import com.couplefinance.identity.domain.UserAccount;
import com.couplefinance.identity.domain.UserAccountRepository;
import com.couplefinance.identity.domain.UserAccountStatus;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UserId;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/** Resolves the current user from the validated JWT ({@code sub} claim = user id, security.md §3). */
@Service
class SecurityContextCurrentUser implements CurrentUser {

    private final UserAccountRepository userAccounts;

    SecurityContextCurrentUser(UserAccountRepository userAccounts) {
        this.userAccounts = userAccounts;
    }

    @Override
    public UserId requireActiveUser() {
        UUID userId = subjectOf(SecurityContextHolder.getContext().getAuthentication());
        UserAccount account = userAccounts.findById(userId)
                .filter(found -> found.status() != UserAccountStatus.DELETED)
                .orElseThrow(() -> new BadCredentialsException("The token does not designate an existing account."));
        if (account.status() != UserAccountStatus.ACTIVE) {
            throw new ApplicationException(IdentityErrorCode.EMAIL_NOT_VERIFIED,
                    "The email address of this account must be verified first.");
        }
        return new UserId(account.id());
    }

    private static UUID subjectOf(Authentication authentication) {
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            throw new InsufficientAuthenticationException("A bearer token is required.");
        }
        try {
            return UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException invalidSubject) {
            throw new BadCredentialsException("The token subject is not a user id.");
        }
    }
}
