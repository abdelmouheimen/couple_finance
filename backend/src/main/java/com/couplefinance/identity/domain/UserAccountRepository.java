package com.couplefinance.identity.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface UserAccountRepository extends Repository<UserAccount, UUID> {

    Optional<UserAccount> findById(UUID id);
}
