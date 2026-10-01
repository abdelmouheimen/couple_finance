package com.couplefinance.household;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.couplefinance.household.application.CreateHouseholdCommand;
import com.couplefinance.household.application.CreateHouseholdService;
import com.couplefinance.household.application.HouseholdErrorCode;
import com.couplefinance.household.domain.Household;
import com.couplefinance.household.domain.HouseholdRepository;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/** Persistence of the Household aggregate and the database-level guarantees behind BR-HH-01/02/16. */
@IntegrationTest
class HouseholdPersistenceIntegrationTest {

    @Autowired
    CreateHouseholdService createHousehold;

    @Autowired
    HouseholdRepository households;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    @Test
    void household_aggregate_is_persisted_and_reloaded() {
        UserId creator = new UserId(users.active());

        Household created = createHousehold.create(creator,
                new CreateHouseholdCommand("Notre foyer", "EUR", "Europe/Paris", 5));

        transactions.executeWithoutResult(status -> {
            Household reloaded = households.findById(created.id().value()).orElseThrow();
            assertThat(reloaded.name()).isEqualTo("Notre foyer");
            assertThat(reloaded.currency()).isEqualTo("EUR");
            assertThat(reloaded.timezone().getId()).isEqualTo("Europe/Paris");
            assertThat(reloaded.currentPeriodStartDay()).isEqualTo(5);
            assertThat(reloaded.activeMembers()).singleElement().satisfies(member -> {
                assertThat(member.userId()).isEqualTo(creator.value());
                assertThat(member.seat()).isEqualTo((short) 1);
            });
        });
        assertThat(households.hasActiveMembership(creator.value())).isTrue();
    }

    @Test
    void BR_HH_16_creation_is_atomic_under_concurrent_requests_of_the_same_user() throws Exception {
        UserId creator = new UserId(users.active());
        int attempts = 6;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Household>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                String name = "Concurrent " + i;
                Callable<Household> attempt = () -> {
                    start.await();
                    return createHousehold.create(creator, new CreateHouseholdCommand(name, null, null, null));
                };
                results.add(executor.submit(attempt));
            }
            start.countDown();

            int created = 0;
            for (Future<Household> result : results) {
                try {
                    result.get();
                    created++;
                } catch (ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOfSatisfying(ApplicationException.class, conflict ->
                            assertThat(conflict.errorCode()).isEqualTo(HouseholdErrorCode.ALREADY_IN_HOUSEHOLD));
                }
            }
            assertThat(created).isEqualTo(1);
        }

        assertThat(count("SELECT count(*) FROM household.household WHERE created_by = :user", creator.value()))
                .as("losing attempts left no household behind").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM household.household_member WHERE user_id = :user", creator.value()))
                .isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM household.audit_event e
                JOIN household.household h ON h.id = e.household_id WHERE h.created_by = :user
                """, creator.value())).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM household.household h
                WHERE h.created_by = :user
                  AND NOT EXISTS (SELECT 1 FROM household.household_member m WHERE m.household_id = h.id)
                """, creator.value())).as("no household without member").isZero();
    }

    @Test
    void BR_HH_02_database_rejects_a_second_active_membership_of_the_same_user() {
        UUID user = users.active();
        UUID first = insertHousehold(user);
        insertMember(first, user, 1);
        UUID second = insertHousehold(user);

        assertThatThrownBy(() -> insertMember(second, user, 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_member_user_active");
    }

    @Test
    void BR_HH_01_database_rejects_a_third_seat_and_a_taken_seat() {
        UUID creator = users.active();
        UUID household = insertHousehold(creator);
        insertMember(household, creator, 1);

        assertThatThrownBy(() -> insertMember(household, users.active(), 3))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_household_member_seat");
        assertThatThrownBy(() -> insertMember(household, users.active(), 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_member_seat_active");
    }

    @Test
    void database_rejects_invalid_household_names() {
        UUID user = users.active();

        assertThatThrownBy(() -> insertHousehold(user, " padded "))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_household_name");
        assertThatThrownBy(() -> insertHousehold(user, ""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_household_name");
        assertThatThrownBy(() -> insertHousehold(user, "a".repeat(101)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_household_name");
    }

    @Test
    void database_rejects_an_unsupported_currency() {
        assertThatThrownBy(() -> jdbc.sql("""
                        INSERT INTO household.household (id, name, currency, timezone, status, tracking_start_date,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (:id, 'Home', 'XXX', 'Europe/Paris', 'ACTIVE', current_date, now(), :user, now(), :user)
                        """)
                .param("id", UUID.randomUUID())
                .param("user", users.active())
                .update())
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_household_currency");
    }

    private UUID insertHousehold(UUID creator) {
        return insertHousehold(creator, "Home");
    }

    private UUID insertHousehold(UUID creator, String name) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO household.household (id, name, currency, timezone, status, tracking_start_date,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (:id, :name, 'EUR', 'Europe/Paris', 'ACTIVE', current_date, now(), :user, now(), :user)
                        """)
                .param("id", id)
                .param("name", name)
                .param("user", creator)
                .update();
        return id;
    }

    private void insertMember(UUID household, UUID user, int seat) {
        jdbc.sql("""
                        INSERT INTO household.household_member (household_id, user_id, seat, joined_at)
                        VALUES (:household, :user, :seat, :joinedAt)
                        """)
                .param("household", household)
                .param("user", user)
                .param("seat", seat)
                .param("joinedAt", OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC))
                .update();
    }

    private long count(String sql, UUID user) {
        return jdbc.sql(sql).param("user", user).query(Long.class).single();
    }
}
