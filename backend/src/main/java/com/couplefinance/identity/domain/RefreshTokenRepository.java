package com.couplefinance.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends Repository<RefreshToken, UUID> {

    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findById(UUID id);

    @Query("select new com.couplefinance.identity.domain.RefreshTokenRef(t.id, t.sessionId) "
            + "from RefreshToken t where t.tokenHash = :hash")
    Optional<RefreshTokenRef> findRefByHash(@Param("hash") byte[] hash);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.sessionId = :sessionId and t.revokedAt is null")
    int revokeAllOfSession(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update RefreshToken t set t.revokedAt = :now where t.revokedAt is null and t.sessionId in "
            + "(select s.id from Session s where s.userId = :userId)")
    int revokeAllOfUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
