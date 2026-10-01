package com.couplefinance.household.domain;

import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
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
 * Budget period start day effective from a date (BR-HH-05, BR-HH-07). Part of the {@link Household} aggregate;
 * later changes add rules, past ones are never altered.
 */
@Entity
@Table(schema = "household", name = "period_rule")
public class PeriodRule {

    public static final int MIN_START_DAY = 1;
    public static final int MAX_START_DAY = 28;

    @EmbeddedId
    private Key key;

    @MapsId("householdId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "household_id")
    private Household household;

    @Column(nullable = false)
    private short startDay;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private UUID createdBy;

    protected PeriodRule() {
        // for JPA
    }

    PeriodRule(Household household, LocalDate effectiveFrom, int startDay, Instant createdAt, UUID createdBy) {
        if (startDay < MIN_START_DAY || startDay > MAX_START_DAY) {
            throw new IllegalArgumentException("Period start day must be between 1 and 28: " + startDay);
        }
        this.key = new Key(household.id().value(), effectiveFrom);
        this.household = household;
        this.startDay = (short) startDay;
        this.createdAt = createdAt;
        this.createdBy = createdBy;
    }

    public LocalDate effectiveFrom() {
        return key.effectiveFrom;
    }

    public int startDay() {
        return startDay;
    }

    @Embeddable
    static class Key implements Serializable {

        private UUID householdId;
        private LocalDate effectiveFrom;

        protected Key() {
            // for JPA
        }

        Key(UUID householdId, LocalDate effectiveFrom) {
            this.householdId = householdId;
            this.effectiveFrom = effectiveFrom;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key
                    && householdId.equals(key.householdId)
                    && effectiveFrom.equals(key.effectiveFrom);
        }

        @Override
        public int hashCode() {
            return 31 * householdId.hashCode() + effectiveFrom.hashCode();
        }
    }
}
