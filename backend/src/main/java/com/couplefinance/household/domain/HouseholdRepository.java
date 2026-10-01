package com.couplefinance.household.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface HouseholdRepository extends Repository<Household, UUID> {

    Household saveAndFlush(Household household);

    Optional<Household> findById(UUID id);

    /** Whether the user is an active member of any household (BR-HH-02). */
    @Query("select count(m) > 0 from HouseholdMember m where m.key.userId = :userId and m.leftAt is null")
    boolean hasActiveMembership(UUID userId);

    /** The ACTIVE household of which the user is an active member (BR-HH-02: at most one).
     * Dissolved households are reachable only through the archive window ({@link #findArchivesFor}). */
    @Query("""
            select h from Household h join fetch h.periodRules join h.members m
            where m.key.userId = :userId and m.leftAt is null and h.status = 'ACTIVE'
            """)
    Optional<Household> findActiveFor(UUID userId);

    /** Dissolved households the user left with unexpired archive access (BR-HH-10), latest expiry first. */
    @Query("""
            select h from Household h join fetch h.periodRules join h.members m
            where m.key.userId = :userId and m.archiveAccessUntil > :now and h.status = 'DISSOLVED'
            order by m.archiveAccessUntil desc
            """)
    List<Household> findArchivesFor(UUID userId, Instant now);
}
