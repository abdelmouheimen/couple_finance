package com.couplefinance.platform;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.couplefinance.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class LiquibaseMigrationIntegrationTest {

    @Autowired
    JdbcClient jdbc;

    @Test
    void master_changelog_is_applied_on_startup_in_module_dependency_order() {
        List<String> applied = jdbc.sql("SELECT id FROM databasechangelog WHERE id NOT LIKE 'test-%' ORDER BY orderexecuted")
                .query(String.class)
                .list();

        assertThat(applied).containsExactly(
                "infra-0001-create-btree-gist-extension",
                "infra-0002-create-infra-schema",
                "infra-0003-create-idempotency-key",
                "infra-0004-create-modulith-event-publication",
                "identity-0001-create-identity-schema",
                "identity-0002-create-user-account",
                "identity-0003-create-session",
                "identity-0004-create-refresh-token",
                "household-0001-create-household-schema",
                "household-0002-create-currency",
                "household-0002-seed-currencies",
                "household-0003-create-household",
                "household-0004-create-household-member",
                "household-0005-create-period-rule",
                "household-0006-create-audit-event",
                "household-0007-create-member-archive-index",
                "household-0008-create-budget-period",
                "household-0009-create-invitation",
                "categorization-0001-create-categorization-schema",
                "categorization-0002-create-category",
                "categorization-0003-seed-system-categories",
                "categorization-0004-create-merchant-rule",
                "categorization-0005-create-merchant-correction",
                "expense-0001-create-expense-schema",
                "expense-0002-create-expense",
                "expense-0003-create-expense-item",
                "expense-0004-create-items-consistency-trigger",
                "expense-0005-create-audit-event",
                "expense-0006-create-refund-index",
                "expense-0007-create-history-indexes",
                "expense-0008-defer-expense-item-uniqueness",
                "expense-0009-create-purge-indexes",
                "budget-0001-create-budget-schema",
                "budget-0002-create-budget",
                "budget-0003-create-audit-event",
                "budget-0004-create-budget-category");
    }

    @Test
    void btree_gist_extension_is_installed() {
        assertThat(jdbc.sql("SELECT count(*) FROM pg_extension WHERE extname = 'btree_gist'")
                .query(Integer.class)
                .single())
                .isEqualTo(1);
    }

    @Test
    void infra_schema_exists() {
        assertThat(jdbc.sql("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'infra'")
                .query(Integer.class)
                .single())
                .isEqualTo(1);
    }
}
