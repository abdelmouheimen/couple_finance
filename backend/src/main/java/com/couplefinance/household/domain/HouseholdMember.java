package com.couplefinance.household.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Membership of a user in a household (domain-model.md §4.1). Belongs to the {@link Household} aggregate and is
 * only created through it. Members have identical rights (BR-HH-03): the seat only enforces the two-member
 * limit, it carries no privilege.
 */
@Entity
@Table(schema = "household", name = "household_member")
public class HouseholdMember {

    /** Seat of the member who created the household. */
    static final short CREATOR_SEAT = 1;

    @EmbeddedId
    private Key key;

    @MapsId("householdId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    @Column(nullable = false)
    private short seat;

    @Column(nullable = false)
    private Instant joinedAt;

    private Instant leftAt;

    private Instant archiveAccessUntil;

    protected HouseholdMember() {
        // for JPA
    }

    HouseholdMember(Household household, UUID userId, short seat, Instant joinedAt) {
        this.key = new Key(household.id().value(), userId);
        this.household = household;
        this.seat = seat;
        this.joinedAt = joinedAt;
    }

    public UUID userId() {
        return key.userId;
    }

    public short seat() {
        return seat;
    }

    public Instant joinedAt() {
        return joinedAt;
    }

    public boolean isActive() {
        return leftAt == null;
    }

    @Embeddable
    static class Key implements Serializable {

        private UUID householdId;
        private UUID userId;

        protected Key() {
            // for JPA
        }

        Key(UUID householdId, UUID userId) {
            this.householdId = householdId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && householdId.equals(key.householdId) && userId.equals(key.userId);
        }

        @Override
        public int hashCode() {
            return 31 * householdId.hashCode() + userId.hashCode();
        }
    }
}
