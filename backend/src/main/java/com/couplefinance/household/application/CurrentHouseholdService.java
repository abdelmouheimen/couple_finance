package com.couplefinance.household.application;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdAccessErrorCode;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.domain.Household;
import com.couplefinance.household.domain.HouseholdRepository;
import com.couplefinance.household.domain.HouseholdStatus;
import com.couplefinance.identity.api.CurrentUser;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UserId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves the household of the authenticated user: the active membership first (BR-HH-02), otherwise the
 * archive access of a dissolved household still within its window (BR-HH-10). The household id never comes from
 * the client.
 */
@Service
class CurrentHouseholdService implements CurrentHousehold, GetCurrentHousehold {

    private final CurrentUser currentUser;
    private final HouseholdRepository households;
    private final Clock clock;

    CurrentHouseholdService(CurrentUser currentUser, HouseholdRepository households, Clock clock) {
        this.currentUser = currentUser;
        this.households = households;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public HouseholdContext currentHousehold() {
        return findCurrentHousehold().orElseThrow(CurrentHouseholdService::notFound);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<HouseholdContext> findCurrentHousehold() {
        UserId user = currentUser.requireActiveUser();
        return resolve(user).map(resolved -> resolved.toContext(user));
    }

    @Override
    @Transactional(readOnly = true)
    public Household getCurrentHousehold() {
        return resolve(currentUser.requireActiveUser()).map(Resolved::household)
                .orElseThrow(CurrentHouseholdService::notFound);
    }

    private Optional<Resolved> resolve(UserId user) {
        Optional<Household> active = households.findActiveFor(user.value());
        if (active.isPresent()) {
            return active.map(household -> new Resolved(household, HouseholdContext.Role.MEMBER));
        }
        List<Household> archives = households.findArchivesFor(user.value(), clock.instant());
        return archives.stream().findFirst()
                .map(household -> new Resolved(household, HouseholdContext.Role.ARCHIVE_READER));
    }

    private static ApplicationException notFound() {
        return new ApplicationException(HouseholdAccessErrorCode.HOUSEHOLD_NOT_FOUND, "Household not found.");
    }

    private record Resolved(Household household, HouseholdContext.Role role) {

        HouseholdContext toContext(UserId user) {
            HouseholdContext.Status status = household.status() == HouseholdStatus.ACTIVE
                    ? HouseholdContext.Status.ACTIVE : HouseholdContext.Status.DISSOLVED;
            return new HouseholdContext(household.id(), user, status, role);
        }
    }
}
