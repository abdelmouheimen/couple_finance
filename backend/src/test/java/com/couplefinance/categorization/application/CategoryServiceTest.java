package com.couplefinance.categorization.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import com.couplefinance.categorization.domain.CategoryRepository;
import com.couplefinance.categorization.domain.SortOrderAllocator;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.pagination.CursorCodec;
import org.junit.jupiter.api.Test;

/** Use-case rules that do not need a database: BR-CAT-02 name clash, BR-HH-10 write guard. */
class CategoryServiceTest {

    private final HouseholdId household = new HouseholdId(UUID.randomUUID());
    private final UserId user = new UserId(UUID.randomUUID());
    private final CurrentHousehold currentHousehold = mock(CurrentHousehold.class);
    private final CategoryRepository repository = mock(CategoryRepository.class);
    private final SortOrderAllocator sortOrders = mock(SortOrderAllocator.class);
    private final CategoryService service = new CategoryService(currentHousehold, repository, sortOrders,
            new CursorCodec(new byte[32]), Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));

    private void context(HouseholdContext.Status status, HouseholdContext.Role role) {
        when(currentHousehold.currentHousehold()).thenReturn(new HouseholdContext(household, user, status, role));
    }

    @Test
    void BR_CAT_02_name_unique_case_insensitive() {
        context(HouseholdContext.Status.ACTIVE, HouseholdContext.Role.MEMBER);
        when(repository.nameExists(household.value(), "groceries")).thenReturn(true);

        assertThatThrownBy(() -> service.create("  groceries "))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("CATEGORY_NAME_ALREADY_EXISTS"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void BR_HH_10_archive_reader_cannot_create() {
        context(HouseholdContext.Status.DISSOLVED, HouseholdContext.Role.ARCHIVE_READER);

        assertThatThrownBy(() -> service.create("Pets"))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("HOUSEHOLD_READ_ONLY"));
        verify(sortOrders, never()).next(any());
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void BR_HH_10_dissolved_household_rejects_updates() {
        context(HouseholdContext.Status.DISSOLVED, HouseholdContext.Role.MEMBER);

        assertThatThrownBy(() -> service.update(UUID.randomUUID(), "Pets", null, "\"0\""))
                .isInstanceOfSatisfying(ApplicationException.class,
                        e -> assertThat(e.errorCode().code()).isEqualTo("HOUSEHOLD_READ_ONLY"));
        verify(repository, never()).findVisibleByIdAndHouseholdId(any(), any());
    }

    @Test
    void create_allocates_the_next_sort_order_and_trims_the_name() {
        context(HouseholdContext.Status.ACTIVE, HouseholdContext.Role.MEMBER);
        when(sortOrders.next(household)).thenReturn(4);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.create("  Pets ");

        assertThat(created.name()).isEqualTo("Pets");
        assertThat(created.sortOrder()).isEqualTo(4);
        assertThat(created.householdId()).isEqualTo(household.value());
        verify(sortOrders).next(household);
    }
}
