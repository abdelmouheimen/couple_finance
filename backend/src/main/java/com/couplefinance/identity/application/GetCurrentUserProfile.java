package com.couplefinance.identity.application;

import com.couplefinance.identity.api.CurrentUser;
import com.couplefinance.identity.domain.UserAccount;
import com.couplefinance.identity.domain.UserAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads the identity of the authenticated user; the id comes from the validated token only. */
@Service
public class GetCurrentUserProfile {

    private final CurrentUser currentUser;
    private final UserAccountRepository accounts;

    GetCurrentUserProfile(CurrentUser currentUser, UserAccountRepository accounts) {
        this.currentUser = currentUser;
        this.accounts = accounts;
    }

    @Transactional(readOnly = true)
    public CurrentUserProfile get() {
        UserAccount account = accounts.findById(currentUser.requireActiveUser().value()).orElseThrow();
        return new CurrentUserProfile(account.id(), account.email(), account.displayName(), account.locale(),
                account.status());
    }
}
