package com.couplefinance.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.identity.domain.UserAccount;
import com.couplefinance.identity.domain.UserAccountRepository;
import com.couplefinance.identity.domain.UserAccountStatus;
import com.couplefinance.shared.error.ApplicationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class SecurityContextCurrentUserTest {

    private final UserAccountRepository accounts = mock(UserAccountRepository.class);
    private final SecurityContextCurrentUser currentUser = new SecurityContextCurrentUser(accounts);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void returns_the_active_user_designated_by_the_token_subject() {
        UUID userId = UUID.randomUUID();
        authenticate(userId.toString());
        UserAccount account = account(userId, UserAccountStatus.ACTIVE);
        when(accounts.findById(userId)).thenReturn(Optional.of(account));

        assertThat(currentUser.requireActiveUser().value()).isEqualTo(userId);
    }

    @Test
    void unauthenticated_request_is_rejected() {
        assertThatThrownBy(currentUser::requireActiveUser).isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void non_jwt_authentication_is_rejected() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("someone", null, List.of()));

        assertThatThrownBy(currentUser::requireActiveUser).isInstanceOf(InsufficientAuthenticationException.class);
    }

    @Test
    void subject_that_is_not_a_user_id_is_rejected() {
        authenticate("not-a-uuid");

        assertThatThrownBy(currentUser::requireActiveUser).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unknown_user_is_rejected() {
        authenticate(UUID.randomUUID().toString());

        assertThatThrownBy(currentUser::requireActiveUser).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void deleted_user_is_rejected() {
        UUID userId = UUID.randomUUID();
        authenticate(userId.toString());
        UserAccount account = account(userId, UserAccountStatus.DELETED);
        when(accounts.findById(userId)).thenReturn(Optional.of(account));

        assertThatThrownBy(currentUser::requireActiveUser).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void user_with_unverified_email_is_forbidden() {
        UUID userId = UUID.randomUUID();
        authenticate(userId.toString());
        UserAccount account = account(userId, UserAccountStatus.PENDING_VERIFICATION);
        when(accounts.findById(userId)).thenReturn(Optional.of(account));

        assertThatThrownBy(currentUser::requireActiveUser)
                .isInstanceOfSatisfying(ApplicationException.class, e ->
                        assertThat(e.errorCode()).isEqualTo(IdentityErrorCode.EMAIL_NOT_VERIFIED));
    }

    private static void authenticate(String subject) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "ES256")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    private static UserAccount account(UUID id, UserAccountStatus status) {
        UserAccount account = mock(UserAccount.class);
        when(account.id()).thenReturn(id);
        when(account.status()).thenReturn(status);
        return account;
    }
}
