package com.couplefinance.household.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Aggregate root of the sharing unit (domain-model.md §4): settings, members and period schedule.
 * All changes to members and period rules go through this class.
 */
@Entity
@Table(schema = "household", name = "household")
public class Household {

    public static final int NAME_MAX_LENGTH = 100;

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private String timezone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private HouseholdStatus status;

    @Column(nullable = false)
    private LocalDate trackingStartDate;

    private Instant dissolvedAt;

    private Instant purgeAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private UUID createdBy;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(nullable = false)
    private UUID updatedBy;

    @Version
    private Long version;

    @OneToMany(mappedBy = "household", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<HouseholdMember> members = new ArrayList<>();

    @OneToMany(mappedBy = "household", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<PeriodRule> periodRules = new ArrayList<>();

    protected Household() {
        // for JPA
    }

    /**
     * Creates a household whose creator is its first active member (seat 1), with the given settings
     * (BR-HH-01, BR-HH-05, BR-HH-16). The caller guarantees the creator is an active user without an active
     * household (BR-HH-02); the database enforces the latter as well.
     *
     * @param name     non-blank, at most {@value #NAME_MAX_LENGTH} characters after trimming
     * @param currency ISO 4217 code of a supported currency
     * @param timezone IANA time-zone id, used to compute "today" for the household
     * @param now      creation instant
     */
    public static Household create(HouseholdId id, String name, String currency, ZoneId timezone,
                                   int periodStartDay, UserId creator, Instant now) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(creator, "creator");
        Objects.requireNonNull(now, "now");
        LocalDate today = LocalDate.ofInstant(now, timezone);

        Household household = new Household();
        household.id = id.value();
        household.name = validName(name);
        household.currency = validCurrency(currency);
        household.timezone = timezone.getId();
        household.status = HouseholdStatus.ACTIVE;
        household.trackingStartDate = today;
        household.createdAt = now;
        household.createdBy = creator.value();
        household.updatedAt = now;
        household.updatedBy = creator.value();
        household.members.add(new HouseholdMember(household, creator.value(), HouseholdMember.CREATOR_SEAT, now));
        household.periodRules.add(new PeriodRule(household, today, periodStartDay, now, creator.value()));
        return household;
    }

    private static String validName(String name) {
        String trimmed = name == null ? "" : name.strip();
        if (trimmed.isEmpty() || trimmed.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException("Household name must have 1 to " + NAME_MAX_LENGTH + " characters.");
        }
        return trimmed;
    }

    private static String validCurrency(String currency) {
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency must be an ISO 4217 code: " + currency);
        }
        return currency;
    }

    public HouseholdId id() {
        return new HouseholdId(id);
    }

    public String name() {
        return name;
    }

    public String currency() {
        return currency;
    }

    public ZoneId timezone() {
        return ZoneId.of(timezone);
    }

    public HouseholdStatus status() {
        return status;
    }

    public LocalDate trackingStartDate() {
        return trackingStartDate;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<HouseholdMember> activeMembers() {
        return members.stream().filter(HouseholdMember::isActive).toList();
    }

    /** The start day in force today: the rule with the latest effective date. */
    public int currentPeriodStartDay() {
        return periodRules.stream()
                .max(Comparator.comparing(PeriodRule::effectiveFrom))
                .orElseThrow()
                .startDay();
    }
}
