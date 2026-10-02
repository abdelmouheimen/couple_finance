package com.couplefinance.categorization.infrastructure;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.categorization.domain.CorrectionStreak;
import com.couplefinance.categorization.domain.MerchantRuleStore;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC adapter of {@link MerchantRuleStore}. Every statement filters on {@code household_id} and on the owner
 * (an {@code IS NULL} test for household level, equality for user level, so that the unique index serves it).
 */
@Repository
class JdbcMerchantRuleStore implements MerchantRuleStore {

    private final JdbcClient jdbc;

    JdbcMerchantRuleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static String ownerClause(@Nullable UUID owner) {
        return owner == null ? "owner_user_id IS NULL" : "owner_user_id = :owner";
    }

    private JdbcClient.StatementSpec scoped(String sql, UUID household, @Nullable UUID owner, String key) {
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("household", household).param("key", key);
        return owner == null ? spec : spec.param("owner", owner);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockScope(UUID householdId, @Nullable UUID ownerUserId, String merchantKey) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "categorization.merchant_rule:" + householdId + ":" + ownerUserId + ":" + merchantKey)
                .query().singleRow();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findActiveRuleCategory(UUID householdId, @Nullable UUID ownerUserId, String merchantKey) {
        return scoped("""
                SELECT r.category_id FROM categorization.merchant_rule r
                JOIN categorization.category c ON c.id = r.category_id
                WHERE r.household_id = :household AND r.merchant_key = :key AND r.%s AND c.archived_at IS NULL
                """.formatted(ownerClause(ownerUserId)), householdId, ownerUserId, merchantKey)
                .query(UUID.class).optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void upsertRule(UUID ruleId, UUID householdId, @Nullable UUID ownerUserId, String merchantKey,
                           int normaliserVersion, UUID categoryId, Instant now) {
        jdbc.sql("""
                INSERT INTO categorization.merchant_rule
                    (id, household_id, owner_user_id, merchant_key, normaliser_version, category_id, hit_count,
                     created_at, updated_at)
                VALUES (:id, :household, :owner, :key, :version, :category, 0, :now, :now)
                ON CONFLICT (household_id, owner_user_id, merchant_key) DO UPDATE
                    SET category_id = EXCLUDED.category_id, normaliser_version = EXCLUDED.normaliser_version,
                        updated_at = EXCLUDED.updated_at
                """)
                .param("id", ruleId).param("household", householdId).param("owner", ownerUserId, java.sql.Types.OTHER)
                .param("key", merchantKey).param("version", (short) normaliserVersion)
                .param("category", categoryId).param("now", utc(now)).update();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CorrectionStreak> findStreak(UUID householdId, @Nullable UUID ownerUserId, String merchantKey) {
        return scoped("""
                SELECT category_id, consecutive_count, last_expense_id FROM categorization.merchant_correction
                WHERE household_id = :household AND merchant_key = :key AND %s
                """.formatted(ownerClause(ownerUserId)), householdId, ownerUserId, merchantKey)
                .query((rs, row) -> new CorrectionStreak(rs.getObject("category_id", UUID.class),
                        rs.getInt("consecutive_count"), rs.getObject("last_expense_id", UUID.class)))
                .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveStreak(UUID rowId, UUID householdId, @Nullable UUID ownerUserId, String merchantKey,
                           CorrectionStreak streak, Instant now) {
        jdbc.sql("""
                INSERT INTO categorization.merchant_correction
                    (id, household_id, owner_user_id, merchant_key, category_id, consecutive_count, last_expense_id,
                     updated_at)
                VALUES (:id, :household, :owner, :key, :category, :count, :expense, :now)
                ON CONFLICT (household_id, owner_user_id, merchant_key) DO UPDATE
                    SET category_id = EXCLUDED.category_id, consecutive_count = EXCLUDED.consecutive_count,
                        last_expense_id = EXCLUDED.last_expense_id, updated_at = EXCLUDED.updated_at
                """)
                .param("id", rowId).param("household", householdId).param("owner", ownerUserId, java.sql.Types.OTHER)
                .param("key", merchantKey)
                .param("category", streak.categoryId()).param("count", (short) streak.count())
                .param("expense", streak.lastExpenseId()).param("now", utc(now)).update();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteStreak(UUID householdId, @Nullable UUID ownerUserId, String merchantKey) {
        scoped("DELETE FROM categorization.merchant_correction WHERE household_id = :household "
                + "AND merchant_key = :key AND " + ownerClause(ownerUserId), householdId, ownerUserId, merchantKey)
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
