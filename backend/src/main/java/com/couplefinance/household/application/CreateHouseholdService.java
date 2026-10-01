package com.couplefinance.household.application;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

import com.couplefinance.household.domain.Household;
import com.couplefinance.household.domain.HouseholdAuditLog;
import com.couplefinance.household.domain.HouseholdRepository;
import com.couplefinance.household.domain.SupportedCurrencies;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a household with its creator as first active member (BR-HH-16). Household, membership, period rule and
 * audit event are written in one transaction: either all exist or none.
 */
@Service
@EnableConfigurationProperties(HouseholdDefaults.class)
public class CreateHouseholdService {

    /** Partial unique index enforcing BR-HH-02 (database-schema.md §5.3). */
    static final String ONE_ACTIVE_HOUSEHOLD_PER_USER = "uq_member_user_active";

    private final HouseholdRepository households;
    private final SupportedCurrencies supportedCurrencies;
    private final HouseholdAuditLog auditLog;
    private final HouseholdDefaults defaults;
    private final BudgetPeriodCalendarService periodCalendar;
    private final Clock clock;

    CreateHouseholdService(HouseholdRepository households, SupportedCurrencies supportedCurrencies,
                           HouseholdAuditLog auditLog, HouseholdDefaults defaults,
                           BudgetPeriodCalendarService periodCalendar, Clock clock) {
        this.households = households;
        this.supportedCurrencies = supportedCurrencies;
        this.auditLog = auditLog;
        this.defaults = defaults;
        this.periodCalendar = periodCalendar;
        this.clock = clock;
    }

    @Transactional
    public Household create(UserId creator, CreateHouseholdCommand command) {
        if (households.hasActiveMembership(creator.value())) {
            throw alreadyInHousehold();
        }
        String currency = command.currency() != null ? command.currency() : defaults.currency();
        if (!supportedCurrencies.isActive(currency)) {
            throw new ApplicationException(HouseholdErrorCode.UNSUPPORTED_CURRENCY,
                    "Currency " + currency + " is not supported.");
        }
        ZoneId timezone = timezone(command.timezone() != null ? command.timezone() : defaults.timezone());
        int periodStartDay = command.periodStartDay() != null ? command.periodStartDay() : defaults.periodStartDay();

        Instant now = clock.instant();
        Household household = Household.create(new HouseholdId(UuidV7.generate(clock)), command.name(), currency,
                timezone, periodStartDay, creator, now);
        try {
            households.saveAndFlush(household);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request of the same user won the race between the check above and the insert.
            if (violates(e, ONE_ACTIVE_HOUSEHOLD_PER_USER)) {
                throw alreadyInHousehold();
            }
            throw e;
        }
        // Same transaction: a household never exists without its calendar, and the first period contains the
        // creation date (BR-HH-05).
        periodCalendar.ensureCalendar(household.id(), timezone, periodStartDay);
        auditLog.householdCreated(household, creator, createdValues(household), now);
        return household;
    }

    private static ZoneId timezone(String id) {
        if (!ZoneId.getAvailableZoneIds().contains(id)) {
            throw new ApplicationException(HouseholdErrorCode.INVALID_TIMEZONE,
                    "Timezone must be an IANA time-zone id such as Europe/Paris.");
        }
        return ZoneId.of(id);
    }

    private static Map<String, Object> createdValues(Household household) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", household.name());
        values.put("currency", household.currency());
        values.put("timezone", household.timezone().getId());
        values.put("periodStartDay", household.currentPeriodStartDay());
        return values;
    }

    private static ApplicationException alreadyInHousehold() {
        return new ApplicationException(HouseholdErrorCode.ALREADY_IN_HOUSEHOLD,
                "You already belong to an active household.");
    }

    private static boolean violates(DataIntegrityViolationException e, String constraint) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraint.equalsIgnoreCase(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
