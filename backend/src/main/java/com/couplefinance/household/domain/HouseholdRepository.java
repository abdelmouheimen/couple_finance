package com.couplefinance.household.domain;

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
}
