package com.couplefinance.household.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.household.domain.Invitation;
import com.couplefinance.household.domain.InvitationStatus;
import com.couplefinance.household.domain.InvitationStore;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcInvitationStore implements InvitationStore {

    private final JdbcClient jdbc;

    JdbcInvitationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Invitation.HouseholdSeats> lockSeats(HouseholdId householdId) {
        Optional<Boolean> active = jdbc.sql(
                        "SELECT status = 'ACTIVE' FROM household.household WHERE id = :id FOR UPDATE")
                .param("id", householdId.value())
                .query(Boolean.class)
                .optional();
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Integer members = jdbc.sql("""
                        SELECT count(*) FROM household.household_member
                        WHERE household_id = :id AND left_at IS NULL
                        """)
                .param("id", householdId.value())
                .query(Integer.class)
                .single();
        return Optional.of(new Invitation.HouseholdSeats(householdId, active.get(), members));
    }

    @Override
    public int revokeAllActive(HouseholdId householdId, Instant now) {
        return jdbc.sql("""
                        UPDATE household.invitation SET status = 'REVOKED', revoked_at = :now
                        WHERE household_id = :householdId AND status = 'ACTIVE'
                        """)
                .param("now", utc(now))
                .param("householdId", householdId.value())
                .update();
    }

    @Override
    public void insert(Invitation invitation, byte[] codeHash) {
        jdbc.sql("""
                        INSERT INTO household.invitation
                            (id, household_id, code_hash, created_by, created_at, expires_at, status)
                        VALUES (:id, :householdId, :codeHash, :createdBy, :createdAt, :expiresAt, :status)
                        """)
                .param("id", invitation.id())
                .param("householdId", invitation.householdId().value())
                .param("codeHash", codeHash)
                .param("createdBy", invitation.createdBy().value())
                .param("createdAt", utc(invitation.createdAt()))
                .param("expiresAt", utc(invitation.expiresAt()))
                .param("status", invitation.status().name())
                .update();
    }

    @Override
    public List<Invitation> findActive(HouseholdId householdId, UserId creator, Instant now) {
        return jdbc.sql("""
                        SELECT id, household_id, created_by, created_at, expires_at, status
                        FROM household.invitation
                        WHERE household_id = :householdId AND created_by = :creator
                          AND status = 'ACTIVE' AND expires_at > :now
                        ORDER BY created_at DESC
                        """)
                .param("householdId", householdId.value())
                .param("creator", creator.value())
                .param("now", utc(now))
                .query((rs, row) -> invitation(rs))
                .list();
    }

    @Override
    public RevokeResult revoke(UUID id, HouseholdId householdId, UserId creator, Instant now) {
        int updated = jdbc.sql("""
                        UPDATE household.invitation SET status = 'REVOKED', revoked_at = :now
                        WHERE id = :id AND household_id = :householdId AND created_by = :creator
                          AND status = 'ACTIVE'
                        """)
                .param("now", utc(now))
                .param("id", id)
                .param("householdId", householdId.value())
                .param("creator", creator.value())
                .update();
        if (updated == 1) {
            return RevokeResult.REVOKED;
        }
        return jdbc.sql("""
                        SELECT status FROM household.invitation
                        WHERE id = :id AND household_id = :householdId AND created_by = :creator
                        """)
                .param("id", id)
                .param("householdId", householdId.value())
                .param("creator", creator.value())
                .query(String.class)
                .optional()
                .map(status -> InvitationStatus.valueOf(status) == InvitationStatus.REVOKED
                        ? RevokeResult.ALREADY_REVOKED : RevokeResult.NOT_REVOCABLE)
                .orElse(RevokeResult.NOT_FOUND);
    }

    private static Invitation invitation(ResultSet rs) throws SQLException {
        return new Invitation(
                rs.getObject("id", UUID.class),
                new HouseholdId(rs.getObject("household_id", UUID.class)),
                new UserId(rs.getObject("created_by", UUID.class)),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(),
                InvitationStatus.valueOf(rs.getString("status")));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
