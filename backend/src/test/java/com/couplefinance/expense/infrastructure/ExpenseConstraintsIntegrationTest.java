package com.couplefinance.expense.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Database-level guarantees of the expense schema, exercised with raw SQL that bypasses the domain model:
 * BR-EXP-08 (deferred items trigger), BR-EXP-07 (personal owner), BR-EXP-05 (household currency), household
 * isolation by composite foreign keys.
 */
@IntegrationTest
class ExpenseConstraintsIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TestUsers users;

    @Autowired
    PlatformTransactionManager transactionManager;

    private UUID household(UUID member, String currency) {
        UUID id = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO household.household (id, name, currency, timezone, status, tracking_start_date,
                            created_at, created_by, updated_at, updated_by)
                        VALUES (:id, 'H', :currency, 'Europe/Paris', 'ACTIVE', current_date, :now, :user, :now, :user)
                        """).param("id", id).param("currency", currency).param("now", now).param("user", member)
                .update();
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:h, :u, 1, :now)").param("h", id).param("u", member).param("now", now).update();
        return id;
    }

    private void insertExpense(UUID id, UUID household, UUID paidBy, UUID owner, UUID createdBy, long minor,
            String currency) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO expense.expense (id, household_id, kind, amount_minor, currency, expense_date,
                            paid_by_user_id, owner_user_id, source, created_at, created_by, updated_at, updated_by)
                        VALUES (:id, :household, 'EXPENSE', :minor, :currency, current_date, :paidBy, :owner,
                            'MANUAL', :now, :createdBy, :now, :createdBy)
                        """).param("id", id).param("household", household).param("minor", minor)
                .param("currency", currency).param("paidBy", paidBy).param("owner", owner, java.sql.Types.OTHER)
                .param("createdBy", createdBy).param("now", now).update();
    }

    private void insertItem(UUID expense, UUID household, int position, long minor) {
        jdbc.sql("INSERT INTO expense.expense_item (id, expense_id, household_id, position, category_id, "
                + "amount_minor) VALUES (:id, :expense, :household, :position, :category, :minor)")
                .param("id", UUID.randomUUID()).param("expense", expense).param("household", household)
                .param("position", position).param("category", UUID.randomUUID()).param("minor", minor).update();
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    @Test
    void BR_EXP_08_the_database_accepts_items_summing_to_the_amount() {
        UUID user = users.active();
        UUID household = household(user, "EUR");
        UUID expense = UUID.randomUUID();

        inTransaction(() -> {
            insertExpense(expense, household, user, null, user, 1250, "EUR");
            insertItem(expense, household, 1, 1000);
            insertItem(expense, household, 2, 250);
        });

        assertThat(jdbc.sql("SELECT count(*) FROM expense.expense_item WHERE expense_id = :id").param("id", expense)
                .query(Long.class).single()).isEqualTo(2L);
    }

    @Test
    void BR_EXP_08_the_database_rejects_items_that_do_not_sum_to_the_amount_at_commit() {
        UUID user = users.active();
        UUID household = household(user, "EUR");
        UUID expense = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
            insertExpense(expense, household, user, null, user, 1250, "EUR");
            insertItem(expense, household, 1, 1000);
            insertItem(expense, household, 2, 249);
        })).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count(expense)).isZero();
    }

    @Test
    void BR_EXP_08_the_database_rejects_an_expense_without_items_at_commit() {
        UUID user = users.active();
        UUID household = household(user, "EUR");
        UUID expense = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> insertExpense(expense, household, user, null, user, 500, "EUR")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(count(expense)).isZero();
    }

    @Test
    void BR_EXP_08_the_database_rejects_a_later_change_of_an_item_that_breaks_the_sum() {
        UUID user = users.active();
        UUID household = household(user, "EUR");
        UUID expense = UUID.randomUUID();
        inTransaction(() -> {
            insertExpense(expense, household, user, null, user, 500, "EUR");
            insertItem(expense, household, 1, 500);
        });

        assertThatThrownBy(() -> jdbc.sql("UPDATE expense.expense_item SET amount_minor = 499 WHERE expense_id = :id")
                .param("id", expense).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.sql("UPDATE expense.expense SET amount_minor = 501 WHERE id = :id")
                .param("id", expense).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void BR_EXP_08_one_item_per_category_and_at_most_ten_positions() {
        UUID user = users.active();
        UUID household = household(user, "EUR");
        UUID expense = UUID.randomUUID();
        UUID category = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
            insertExpense(expense, household, user, null, user, 200, "EUR");
            for (int position = 1; position <= 2; position++) {
                jdbc.sql("INSERT INTO expense.expense_item (id, expense_id, household_id, position, category_id, "
                        + "amount_minor) VALUES (:id, :e, :h, :p, :c, 100)").param("id", UUID.randomUUID())
                        .param("e", expense).param("h", household).param("p", position).param("c", category).update();
            }
        })).isInstanceOf(DataIntegrityViolationException.class);

        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> inTransaction(() -> {
            insertExpense(other, household, user, null, user, 100, "EUR");
            insertItem(other, household, 11, 100);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void BR_EXP_07_a_personal_expense_must_belong_to_its_payer_and_creator() {
        UUID user = users.active();
        UUID partner = users.active();
        UUID household = household(user, "EUR");
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:h, :u, 2, now())").param("h", household).param("u", partner).update();
        UUID expense = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
            insertExpense(expense, household, partner, user, user, 100, "EUR");
            insertItem(expense, household, 1, 100);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void BR_EXP_05_the_currency_must_be_the_household_currency() {
        UUID user = users.active();
        UUID household = household(user, "EUR");
        UUID expense = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
            insertExpense(expense, household, user, null, user, 100, "USD");
            insertItem(expense, household, 1, 100);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void household_isolation_a_user_of_another_household_cannot_be_payer_or_creator() {
        UUID user = users.active();
        UUID outsider = users.active();
        UUID household = household(user, "EUR");
        household(outsider, "EUR");
        UUID asPayer = UUID.randomUUID();
        UUID asCreator = UUID.randomUUID();

        assertThatThrownBy(() -> inTransaction(() -> {
            insertExpense(asPayer, household, outsider, null, user, 100, "EUR");
            insertItem(asPayer, household, 1, 100);
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> inTransaction(() -> {
            insertExpense(asCreator, household, user, null, outsider, 100, "EUR");
            insertItem(asCreator, household, 1, 100);
        })).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void BR_MON_07_the_database_rejects_non_positive_amounts() {
        UUID user = users.active();
        UUID household = household(user, "EUR");

        assertThatThrownBy(() -> insertExpense(UUID.randomUUID(), household, user, null, user, 0, "EUR"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertExpense(UUID.randomUUID(), household, user, null, user, -5, "EUR"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long count(UUID expense) {
        return jdbc.sql("SELECT count(*) FROM expense.expense WHERE id = :id").param("id", expense)
                .query(Long.class).single();
    }
}
