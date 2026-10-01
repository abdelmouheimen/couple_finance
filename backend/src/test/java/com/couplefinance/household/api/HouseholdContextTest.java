package com.couplefinance.household.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.junit.jupiter.api.Test;

/** BR-HH-03 / BR-HH-10: write guard of the household context. */
class HouseholdContextTest {

    private static HouseholdContext context(HouseholdContext.Status status, HouseholdContext.Role role) {
        return new HouseholdContext(new HouseholdId(UUID.randomUUID()), new UserId(UUID.randomUUID()), status, role);
    }

    @Test
    void BR_HH_03_active_household_member_can_write() {
        HouseholdContext context = context(HouseholdContext.Status.ACTIVE, HouseholdContext.Role.MEMBER);

        assertThat(context.isWritable()).isTrue();
        assertThatCode(context::requireWritable).doesNotThrowAnyException();
    }

    @Test
    void BR_HH_10_dissolved_household_is_read_only() {
        HouseholdContext context = context(HouseholdContext.Status.DISSOLVED, HouseholdContext.Role.ARCHIVE_READER);

        assertThat(context.isWritable()).isFalse();
        assertThatThrownBy(context::requireWritable).isInstanceOfSatisfying(ApplicationException.class, e -> {
            assertThat(e.errorCode().code()).isEqualTo("HOUSEHOLD_READ_ONLY");
            assertThat(e.errorCode().status().value()).isEqualTo(403);
        });
    }

    @Test
    void BR_HH_10_dissolved_household_rejects_writes_even_for_a_member_role() {
        assertThat(context(HouseholdContext.Status.DISSOLVED, HouseholdContext.Role.MEMBER).isWritable()).isFalse();
    }

    @Test
    void archive_reader_cannot_write() {
        assertThat(context(HouseholdContext.Status.ACTIVE, HouseholdContext.Role.ARCHIVE_READER).isWritable())
                .isFalse();
    }
}
