package com.couplefinance.identity.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface UserAccountRepository extends Repository<UserAccount, UUID> {

    Optional<UserAccount> findById(UUID id);

    /** Login lookup: case-insensitive on the email, matching {@code uq_user_account_email}; tombstones excluded. */
    @Query("select u from UserAccount u where lower(u.email) = :email and u.status <> "
            + "com.couplefinance.identity.domain.UserAccountStatus.DELETED")
    Optional<UserAccount> findLoginCandidateByLowerEmail(@Param("email") String email);
}
