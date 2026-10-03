package com.couplefinance.identity.domain;

import java.util.UUID;

import org.springframework.data.repository.Repository;

public interface RefreshTokenRepository extends Repository<RefreshToken, UUID> {

    RefreshToken save(RefreshToken token);
}
