package com.couplefinance.expense.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.categorization.api.MerchantNames;
import com.couplefinance.categorization.api.NormalisedMerchant;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.error.CommonErrorCode;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import com.couplefinance.shared.id.UuidV7;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.OptimisticLock;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * Aggregate root of the household ledger (domain-model.md section 7): an expense with its category items. The
 * factory enforces the creation invariants - BR-EXP-01 fields, BR-EXP-05 currency, BR-EXP-06 date window,
 * BR-EXP-07 visibility, BR-EXP-08 items, BR-MON-07 amount - so that no code path can build an invalid expense;
 * the database re-checks the same invariants (constraints and the deferred items trigger).
 *
 * <p>Checks that need other modules (paid-by is a household member, categories are usable) belong to the use
 * case, through {@code household.api} and {@code categorization.api}.
 */
@Entity
@Table(schema = "expense", name = "expense")
public class Expense {

    public static final int MAX_ITEMS = 10;
    public static final int NOTE_MAX_LENGTH = 500;
    public static final int MERCHANT_MAX_LENGTH = 120;
    public static final int MAX_AGE_YEARS = 5;
    public static final int MAX_DAYS_AHEAD = 1;

    @Id
    private UUID id;

    private UUID householdId;

    @Enumerated(EnumType.STRING)
    private ExpenseKind kind;

    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency;

    private LocalDate expenseDate;

    private UUID paidByUserId;

    /** {@code null} = SHARED; set = PERSONAL (BR-EXP-07). */
    private UUID ownerUserId;

    private String merchantDisplay;

    private String merchantKey;

    private Short merchantNormaliserVersion;

    private String note;

    /** The original expense of a linked REFUND (BR-EXP-03); {@code null} otherwise. */
    private UUID refundOfExpenseId;

    @Enumerated(EnumType.STRING)
    private ExpenseSource source;

    private Instant createdAt;

    private UUID createdBy;

    private Instant updatedAt;

    private UUID updatedBy;

    /** Logical deletion marker (BR-EXP-11): a deleted expense is excluded from every live-refund sum. */
    private Instant deletedAt;

    @Version
    private Long version;

    // Items are written together with the expense at creation: adding them is not a modification of the version.
    @OptimisticLock(excluded = true)
    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "expense_id", nullable = false, updatable = false)
    @OrderBy("position")
    private List<ExpenseItem> items = new ArrayList<>();

    protected Expense() {
        // for JPA
    }

    /**
     * Creates a manual {@code EXPENSE} (BR-EXP-02) in the household of {@code creator}.
     *
     * @param ledger   currency, time zone and maximum amount of the household
     * @param clock    source of "now" and, with the household time zone, of "today" (BR-HH-05)
     * @param sharing  {@code PERSONAL} makes the creator the owner and requires {@code paidBy == creator}
     * @param merchant optional, stored as given (BR-EXP-01); its normalised key is derived for categorisation
     * @throws ApplicationException the first violated rule, with a stable error code
     */
    public static Expense create(HouseholdId household, LedgerProfile ledger, Clock clock, Money amount,
            LocalDate date, List<NewItem> items, UserId paidBy, SharingType sharing, UserId creator,
            @Nullable String merchant, @Nullable String note) {
        return build(ExpenseKind.EXPENSE, null, household, ledger, clock, amount, date, items, paidBy, sharing,
                creator, merchant, note);
    }

    /**
     * Creates a manual {@code REFUND} (BR-EXP-02, BR-EXP-03). A refund that is not linked ({@code original ==
     * null}) behaves like an expense of kind REFUND. A linked refund must have the original's visibility, be dated
     * on or after the original, and keep the sum of live refunds of the original within its amount.
     *
     * @param original        the original, which the caller must have loaded under a row lock and scoped by
     *                        household and visibility; {@code null} for an unlinked refund
     * @param alreadyRefunded sum of the live refunds already linked to {@code original}, computed under that lock
     */
    public static Expense createRefund(HouseholdId household, LedgerProfile ledger, Clock clock, Money amount,
            LocalDate date, List<NewItem> items, UserId paidBy, SharingType sharing, UserId creator,
            @Nullable String merchant, @Nullable String note, @Nullable Expense original,
            Money alreadyRefunded) {
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(sharing, "sharing");
        if (original != null) {
            original.requireRefundable(household, sharing, date, amount, alreadyRefunded);
        }
        return build(ExpenseKind.REFUND, original == null ? null : original.id, household, ledger, clock, amount,
                date, items, paidBy, sharing, creator, merchant, note);
    }

    /**
     * BR-EXP-03 + BR-MON-06: the items of a refund that gives none, one per original category, proportional to the
     * original items. Each share is the floor of {@code amount * originalItem / originalAmount}; the remainder
     * (fewer minor units than items) is handed out one unit at a time, first item first. Shares that stay at zero
     * are dropped (an item is strictly positive), so the result always sums to {@code amount} exactly.
     */
    public List<NewItem> proportionalItems(Money amount) {
        BigInteger total = BigInteger.valueOf(amountMinor);
        BigInteger refund = BigInteger.valueOf(amount.minorUnits());
        long[] shares = new long[items.size()];
        long distributed = 0;
        for (int i = 0; i < shares.length; i++) {
            shares[i] = refund.multiply(BigInteger.valueOf(items.get(i).amountMinor())).divide(total)
                    .longValueExact();
            distributed += shares[i];
        }
        long remainder = amount.minorUnits() - distributed;
        for (int i = 0; remainder > 0 && i < shares.length; i++, remainder--) {
            shares[i]++;
        }
        List<NewItem> result = new ArrayList<>();
        for (int i = 0; i < shares.length; i++) {
            if (shares[i] > 0) {
                result.add(new NewItem(items.get(i).categoryId(),
                        Money.ofMinor(shares[i], amount.currency(), amount.amount().scale()), items.get(i).label()));
            }
        }
        return result;
    }

    /** Domain checks of BR-EXP-03 for a refund of this expense, made under the lock on this row. */
    private void requireRefundable(HouseholdId household, SharingType sharing, LocalDate refundDate, Money amount,
            Money alreadyRefunded) {
        if (!householdId.equals(household.value()) || kind != ExpenseKind.EXPENSE || deletedAt != null) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_REFUND_ORIGINAL_INVALID,
                    "A refund can only be linked to an expense.");
        }
        if (sharing != sharingType()) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_REFUND_VISIBILITY_MISMATCH,
                    "A refund has the same sharing type as its original expense.");
        }
        if (refundDate.isBefore(expenseDate)) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_REFUND_DATE_BEFORE_ORIGINAL,
                    "A refund cannot be dated before its original expense.");
        }
        if (alreadyRefunded.add(amount).minorUnits() > amountMinor) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_REFUND_EXCEEDS_ORIGINAL,
                    "The refunds of an expense cannot exceed its amount.");
        }
    }

    private static Expense build(ExpenseKind kind, @Nullable UUID refundOf, HouseholdId household,
            LedgerProfile ledger, Clock clock, Money amount, LocalDate date, List<NewItem> items, UserId paidBy,
            SharingType sharing, UserId creator, @Nullable String merchant, @Nullable String note) {
        Objects.requireNonNull(household, "household");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(paidBy, "paidBy");
        Objects.requireNonNull(sharing, "sharing");
        Objects.requireNonNull(creator, "creator");

        // BR-EXP-05 + BR-MON-07: household currency, strictly positive, at most the per-currency maximum.
        amount.requirePositiveAtMost(ledger.maxExpense());
        Instant now = clock.instant();
        requireDateInWindow(date, LocalDate.ofInstant(now, ledger.timezone()));
        if (sharing == SharingType.PERSONAL && !paidBy.equals(creator)) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_PERSONAL_PAYER_MISMATCH,
                    "A personal expense must be paid by its creator.");
        }
        requireConsistentItems(amount, items, ledger.maxExpense());

        Expense expense = new Expense();
        expense.id = UuidV7.generate(clock);
        expense.householdId = household.value();
        expense.kind = kind;
        expense.refundOfExpenseId = refundOf;
        expense.amountMinor = amount.minorUnits();
        expense.currency = amount.currency().value();
        expense.expenseDate = date;
        expense.paidByUserId = paidBy.value();
        expense.ownerUserId = sharing == SharingType.PERSONAL ? creator.value() : null;
        expense.applyMerchant(merchant);
        expense.note = validNote(note);
        expense.source = ExpenseSource.MANUAL;
        expense.createdAt = now;
        expense.createdBy = creator.value();
        expense.updatedAt = now;
        expense.updatedBy = creator.value();
        int position = 1;
        for (NewItem item : items) {
            expense.items.add(new ExpenseItem(UuidV7.generate(clock), household.value(), position++,
                    item.categoryId(), item.amount().minorUnits(), validLabel(item.label())));
        }
        return expense;
    }

    /** BR-EXP-06: not more than 1 day after {@code today} and not older than 5 years (both inclusive). */
    static void requireDateInWindow(LocalDate date, LocalDate today) {
        if (date.isAfter(today.plusDays(MAX_DAYS_AHEAD)) || date.isBefore(today.minusYears(MAX_AGE_YEARS))) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_DATE_OUT_OF_RANGE,
                    "The expense date cannot be more than " + MAX_DAYS_AHEAD + " day after today nor older than "
                            + MAX_AGE_YEARS + " years.");
        }
    }

    /** BR-EXP-08: 1 to 10 items, one per category, each positive, summing exactly to the amount. */
    static void requireConsistentItems(Money amount, List<NewItem> items, Money maxAmount) {
        if (items.isEmpty() || items.size() > MAX_ITEMS) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_ITEM_COUNT_INVALID,
                    "An expense has 1 to " + MAX_ITEMS + " items.");
        }
        Set<UUID> categories = new HashSet<>();
        Money sum = Money.zero(amount.currency(), amount.amount().scale());
        for (NewItem item : items) {
            item.amount().requirePositiveAtMost(maxAmount);
            if (!categories.add(item.categoryId())) {
                throw new ApplicationException(ExpenseErrorCode.EXPENSE_ITEM_DUPLICATE_CATEGORY,
                        "At most one item per category.");
            }
            sum = sum.add(item.amount());
        }
        if (!sum.equals(amount)) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_ITEMS_SUM_MISMATCH,
                    "The items must sum exactly to the expense amount.");
        }
    }

    private void applyMerchant(@Nullable String merchant) {
        if (merchant == null || merchant.isBlank()) {
            return;
        }
        if (merchant.length() > MERCHANT_MAX_LENGTH) {
            throw new ApplicationException(CommonErrorCode.VALIDATION_FAILED,
                    "The merchant has at most " + MERCHANT_MAX_LENGTH + " characters.");
        }
        NormalisedMerchant normalised = MerchantNames.normalise(merchant);
        if (normalised.isEmpty()) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_MERCHANT_INVALID,
                    "The merchant must contain letters or digits.");
        }
        this.merchantDisplay = merchant;
        this.merchantKey = normalised.key();
        this.merchantNormaliserVersion = (short) normalised.normaliserVersion();
    }

    private static @Nullable String validNote(@Nullable String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.length() > NOTE_MAX_LENGTH) {
            throw new ApplicationException(CommonErrorCode.VALIDATION_FAILED,
                    "The note has at most " + NOTE_MAX_LENGTH + " characters.");
        }
        return note;
    }

    private static @Nullable String validLabel(@Nullable String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.strip();
        if (trimmed.length() > ExpenseItem.LABEL_MAX_LENGTH) {
            throw new ApplicationException(CommonErrorCode.VALIDATION_FAILED,
                    "An item label has at most " + ExpenseItem.LABEL_MAX_LENGTH + " characters.");
        }
        return trimmed;
    }

    public UUID id() {
        return id;
    }

    public UUID householdId() {
        return householdId;
    }

    public ExpenseKind kind() {
        return kind;
    }

    public long amountMinor() {
        return amountMinor;
    }

    public @Nullable UUID refundOfExpenseId() {
        return refundOfExpenseId;
    }

    public CurrencyCode currency() {
        return new CurrencyCode(currency.strip());
    }

    public LocalDate expenseDate() {
        return expenseDate;
    }

    public UUID paidByUserId() {
        return paidByUserId;
    }

    public @Nullable UUID ownerUserId() {
        return ownerUserId;
    }

    public SharingType sharingType() {
        return ownerUserId == null ? SharingType.SHARED : SharingType.PERSONAL;
    }

    public @Nullable String merchantDisplay() {
        return merchantDisplay;
    }

    /** Normalised merchant key (BR-CAT-07), {@code null} when the expense has no merchant. */
    public @Nullable String merchantKey() {
        return merchantKey;
    }

    public @Nullable Short merchantNormaliserVersion() {
        return merchantNormaliserVersion;
    }

    public @Nullable String note() {
        return note;
    }

    public ExpenseSource source() {
        return source;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public UUID createdBy() {
        return createdBy;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public UUID updatedBy() {
        return updatedBy;
    }

    public List<ExpenseItem> items() {
        return List.copyOf(items);
    }

    public long version() {
        return version == null ? 0 : version;
    }
}
