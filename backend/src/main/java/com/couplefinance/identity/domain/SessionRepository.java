package com.couplefinance.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

public interface SessionRepository extends Repository<Session, UUID> {

    Session save(Session session);

    /** Row lock serialising every refresh and revocation of one session. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Session s where s.id = :id")
    Optional<Session> findByIdForUpdate(@Param("id") UUID id);

    /** Conditional update scoped to the owner; idempotent. Returns the number of sessions revoked (0 or 1). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Session s set s.revokedAt = :now, s.revokeReason = :reason "
            + "where s.id = :id and s.userId = :userId and s.revokedAt is null")
    int revoke(@Param("id") UUID id, @Param("userId") UUID userId, @Param("reason") SessionRevokeReason reason,
               @Param("now") Instant now);

    /** Revokes every active session of a user. Returns the number of sessions revoked. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update Session s set s.revokedAt = :now, s.revokeReason = :reason "
            + "where s.userId = :userId and s.revokedAt is null")
    int revokeAllOfUser(@Param("userId") UUID userId, @Param("reason") SessionRevokeReason reason,
                        @Param("now") Instant now);
}
