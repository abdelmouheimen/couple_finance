package com.couplefinance.household.infrastructure;

import java.time.LocalDate;
import java.util.Objects;

import com.couplefinance.household.api.TrackingStart;
import com.couplefinance.shared.id.HouseholdId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements {@link TrackingStart} (BR-ANA-03) with a single conditional update, so concurrent and out-of-order
 * callers converge on the minimum without a read-modify-write race. The version is bumped so that a stale
 * {@code Household} entity can never write an older, higher tracking start back.
 */
@Repository
class JdbcTrackingStart implements TrackingStart {

    private final JdbcClient jdbc;

    JdbcTrackingStart(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public LocalDate of(HouseholdId household) {
        return jdbc.sql("SELECT tracking_start_date FROM household.household WHERE id = :household")
                .param("household", household.value())
                .query(LocalDate.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException("household not found"));
    }

    /** REQUIRES_NEW: called by an after-commit listener, where the publishing transaction has already completed. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean lowerTo(HouseholdId household, LocalDate candidate) {
        Objects.requireNonNull(candidate, "candidate");
        return jdbc.sql("""
                        UPDATE household.household
                        SET tracking_start_date = :candidate, version = version + 1
                        WHERE id = :household AND tracking_start_date > :candidate
                        """)
                .param("candidate", candidate)
                .param("household", household.value())
                .update() > 0;
    }
}
