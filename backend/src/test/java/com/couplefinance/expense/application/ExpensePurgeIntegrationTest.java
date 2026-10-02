package com.couplefinance.expense.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.expense.api.ExpensePurged;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Retention purge (Issue #22): BR-EXP-11 (deleted expenses purged after 90 days with items and audit rows),
 * BR-DAT (audit retention 24 months). Rows are seeded with raw SQL; time is a fixed instant passed to the job.
 */
@IntegrationTest
class ExpensePurgeIntegrationTest {


    @Autowired
    JdbcClient jdbc;
    @Autowired
    Clock clock;
    @Autowired
    TestUsers users;
    @Autowired
    ExpensePurgeJob job;
    @Autowired
    ExpensePurgeService service;
    @Autowired
    ExpensePurgedProbeListener listener;
    @Autowired
    PlatformTransactionManager transactionManager;

    /**
     * The purge is unscoped, so it also purges rows seeded by other test classes that are older than its cutoffs:
     * the reference time is the application clock (not a far-future instant), so that only rows older than the real
     * retention periods are affected.
     */
    private Instant now;

    @BeforeEach
    void fixNow() {
        now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    }

    private record Fixture(UUID household, UUID user) {}

    private Fixture household() {
        UUID user = users.active();
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO household.household (id, name, currency, timezone, status, tracking_start_date,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (:id, 'H', 'EUR', 'Europe/Paris', 'ACTIVE', current_date, :now, :user, :now, :user)
                        """).param("id", id).param("now", now).param("user", user).update();
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:h, :u, 1, :now)").param("h", id).param("u", user).param("now", now).update();
        return new Fixture(id, user);
    }

    /** Seeds an expense with one item and one audit row; {@code deletedAt} null means live. */
    private UUID expense(Fixture f, Instant deletedAt, UUID refundOf, UUID receiptId) {
        return new TransactionTemplate(transactionManager)
                .execute(status -> insertExpense(f, deletedAt, refundOf, receiptId));
    }

    private UUID insertExpense(Fixture f, Instant deletedAt, UUID refundOf, UUID receiptId) {
        UUID id = UUID.randomUUID();
        OffsetDateTime created = now.minus(400, ChronoUnit.DAYS).atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO expense.expense (id, household_id, kind, amount_minor, currency, expense_date,
                            paid_by_user_id, source, created_at, created_by, updated_at, updated_by, deleted_at,
                            deleted_by, refund_of_expense_id, receipt_id)
                        VALUES (:id, :h, :kind, 500, 'EUR', current_date, :u, 'MANUAL', :created, :u, :created, :u,
                            :deletedAt, :deletedBy, :refundOf, :receipt)
                        """)
                .param("id", id).param("h", f.household).param("u", f.user).param("created", created)
                .param("kind", refundOf == null ? "EXPENSE" : "REFUND")
                .param("deletedAt", deletedAt == null ? null : deletedAt.atOffset(ZoneOffset.UTC),
                        java.sql.Types.TIMESTAMP_WITH_TIMEZONE)
                .param("deletedBy", deletedAt == null ? null : f.user, java.sql.Types.OTHER)
                .param("refundOf", refundOf, java.sql.Types.OTHER)
                .param("receipt", receiptId, java.sql.Types.OTHER).update();
        jdbc.sql("INSERT INTO expense.expense_item (id, expense_id, household_id, position, category_id, "
                + "amount_minor) VALUES (:id, :e, :h, 1, :c, 500)").param("id", UUID.randomUUID()).param("e", id)
                .param("h", f.household).param("c", UUID.randomUUID()).update();
        audit(f, id, created.toInstant());
        return id;
    }

    private UUID audit(Fixture f, UUID entity, Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO expense.audit_event (id, household_id, entity_type, entity_id, action, actor_type,
                            actor_user_id, changes, occurred_at)
                        VALUES (:id, :h, 'EXPENSE', :e, 'UPDATE', 'USER', :u, '{}'::jsonb, :at)
                        """).param("id", id).param("h", f.household).param("e", entity).param("u", f.user)
                .param("at", occurredAt.atOffset(ZoneOffset.UTC)).update();
        return id;
    }

    private long count(String table, String column, UUID id) {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id").param("id", id)
                .query(Long.class).single();
    }

    private boolean exists(UUID expense) {
        return count("expense.expense", "id", expense) == 1;
    }

    private Instant daysAgo(long days) {
        return now.minus(days, ChronoUnit.DAYS);
    }

    @Test
    void BR_EXP_11_deleted_expense_is_purged_with_items_and_audit_after_90_days() {
        Fixture f = household();
        UUID receipt = UUID.randomUUID();
        UUID old = expense(f, daysAgo(90).minusSeconds(1), null, receipt);

        job.run(now);

        assertThat(exists(old)).isFalse();
        assertThat(count("expense.expense_item", "expense_id", old)).isZero();
        assertThat(count("expense.audit_event", "entity_id", old)).isZero();
        assertThat(listener.received()).filteredOn(e -> e.expenseId().equals(old)).singleElement()
                .satisfies(e -> {
                    assertThat(e.householdId()).isEqualTo(f.household);
                    assertThat(e.receiptId()).isEqualTo(receipt);
                });
    }

    @Test
    void BR_EXP_11_boundary_exactly_90_days_is_kept_and_live_or_recent_deleted_rows_are_untouched() {
        Fixture f = household();
        UUID exactly90 = expense(f, daysAgo(90), null, null);
        UUID recent = expense(f, daysAgo(10), null, null);
        UUID live = expense(f, null, null, null);

        job.run(now);

        assertThat(exists(exactly90)).isTrue();
        assertThat(exists(recent)).isTrue();
        assertThat(exists(live)).isTrue();
        assertThat(count("expense.expense_item", "expense_id", exactly90)).isEqualTo(1);
        assertThat(count("expense.audit_event", "entity_id", live)).isEqualTo(1);
        assertThat(listener.received()).extracting(ExpensePurged::expenseId)
                .doesNotContain(exactly90, recent, live);

        job.run(now.plusSeconds(1)); // one second later the boundary row is older than 90 days
        assertThat(exists(exactly90)).isFalse();
        assertThat(exists(recent)).isTrue();
    }

    @Test
    void BR_DAT_audit_rows_older_than_24_months_are_purged_and_the_boundary_is_kept() {
        Fixture f = household();
        UUID live = expense(f, null, null, null);
        Instant cutoff = now.atOffset(ZoneOffset.UTC).minusMonths(24).toInstant();
        UUID older = audit(f, live, cutoff.minusSeconds(1));
        UUID boundary = audit(f, live, cutoff);
        UUID recent = audit(f, live, now.minus(30, ChronoUnit.DAYS));

        job.run(now);

        assertThat(count("expense.audit_event", "id", older)).isZero();
        assertThat(count("expense.audit_event", "id", boundary)).isEqualTo(1);
        assertThat(count("expense.audit_event", "id", recent)).isEqualTo(1);
        assertThat(exists(live)).isTrue();
    }

    @Test
    void purge_is_idempotent() {
        Fixture f = household();
        expense(f, daysAgo(100), null, null);

        job.run(now);
        int events = listener.received().size();
        int[] second = job.run(now);

        assertThat(second[0]).isZero();
        assertThat(listener.received()).hasSize(events);
    }

    @Test
    void BR_EXP_03_an_original_still_referenced_by_a_refund_is_purged_after_the_refund() {
        Fixture f = household();
        UUID original = expense(f, daysAgo(120), null, null);
        UUID refund = expense(f, daysAgo(100), original, null);

        job.run(now);

        assertThat(exists(refund)).isFalse();
        assertThat(exists(original)).isFalse(); // purged by a later batch of the same run
    }

    @Test
    void batches_are_bounded_by_the_requested_size() {
        Fixture f = household();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(expense(f, daysAgo(100 + i), null, null));
        }

        // other tests' leftovers may also be eligible; the bound still holds
        assertThat(service.purgeDeletedExpenses(now, 2)).isEqualTo(2);
        long remaining = ids.stream().filter(this::exists).count();
        assertThat(remaining).isBetween(3L, 5L);
        while (service.purgeDeletedExpenses(now, 10) > 0) {
            // drain
        }
        assertThat(ids.stream().filter(this::exists)).isEmpty();
    }

    @Test
    void two_job_instances_never_process_the_same_expense() throws Exception {
        Fixture f = household();
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ids.add(expense(f, daysAgo(100), null, null));
        }
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int t = 0; t < 2; t++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    int total = 0;
                    int removed;
                    do {
                        removed = service.purgeDeletedExpenses(now, 7);
                        total += removed;
                    } while (removed > 0);
                    return total;
                }));
            }
            int total = 0;
            for (Future<Integer> run : runs) {
                total += run.get();
            }
            assertThat(total).isEqualTo(60);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ids.stream().filter(this::exists)).isEmpty();
        assertThat(listener.received()).extracting(ExpensePurged::expenseId).containsAll(ids)
                .doesNotHaveDuplicates();
    }
}
