package com.couplefinance.expense.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    /** BR-EXP-11: a deleted expense can be restored for 90 days. */
    public static final int RESTORE_WINDOW_DAYS = 90;

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

    private UUID deletedBy;

    @Version
    private Long version;

    // Items are written together with the expense at creation: adding them is not a modification of the version.
    // An edit of the items bumps the version through updatedAt, see update().
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
            this.merchantDisplay = null;
            this.merchantKey = null;
            this.merchantNormaliserVersion = null;
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

    /** Everything an edit may change, for the audit diff and the no-op detection (BR-EXP-10). */
    public ExpenseSnapshot snapshot() {
        return new ExpenseSnapshot(amountMinor, currency.strip(), expenseDate, paidByUserId, ownerUserId,
                merchantDisplay, note,
                items.stream().map(i -> new ExpenseSnapshot.Item(i.categoryId(), i.amountMinor(), i.label()))
                        .toList());
    }

    /**
     * BR-EXP-14: the categories of {@code items} that must be active - those of items that are not identical
     * (category, amount, label) to an item the expense already has. An unchanged item on a since-archived category
     * stays valid.
     */
    public Set<UUID> categoriesRequiringActiveStatus(List<NewItem> newItems) {
        Set<ExpenseSnapshot.Item> existing = new HashSet<>(snapshot().items());
        Set<UUID> required = new HashSet<>();
        for (NewItem item : newItems) {
            if (!existing.contains(new ExpenseSnapshot.Item(item.categoryId(), item.amount().minorUnits(),
                    normalisedLabel(item.label())))) {
                required.add(item.categoryId());
            }
        }
        return required;
    }

    /**
     * Facts about the refunds around an expense being edited, read by the caller under the lock on the original
     * (BR-EXP-03, domain-model.md section 7.3).
     *
     * @param original          the original of this expense when it is a linked refund, else {@code null}
     * @param otherRefundsMinor live refunds of the original other than this one
     * @param ownRefundsMinor   live refunds linked to this expense
     * @param earliestOwnRefund date of the earliest live refund linked to this expense, if any
     */
    public record RefundState(@Nullable Expense original, long otherRefundsMinor, long ownRefundsMinor,
            @Nullable LocalDate earliestOwnRefund) {

        public static RefundState none() {
            return new RefundState(null, 0, 0, null);
        }
    }

    /**
     * Edits this expense (BR-EXP-09): full replacement of amount, date, items, payer, sharing type, merchant and
     * note. Kind, refund link, source and creator never change. The caller has loaded the row under a lock, scoped
     * by household and visibility, and has checked the version (BR-EXP-12), the payer membership and the
     * categories (BR-EXP-14, through {@link #categoriesRequiringActiveStatus}).
     *
     * <p>The date window is checked only when the date changes: validation-time rules are not re-checked as time
     * passes. When nothing changes the expense is left untouched.
     *
     * @return whether anything changed (the version is then incremented at flush)
     * @throws ApplicationException the first violated rule, with a stable error code
     */
    public boolean update(UserId editor, LedgerProfile ledger, Clock clock, Money amount, LocalDate date,
            List<NewItem> newItems, UserId paidBy, SharingType sharing, @Nullable String merchant,
            @Nullable String newNote, RefundState refunds) {
        Objects.requireNonNull(editor, "editor");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(newItems, "items");
        Objects.requireNonNull(paidBy, "paidBy");
        Objects.requireNonNull(sharing, "sharing");
        Objects.requireNonNull(refunds, "refunds");
        // BR-EXP-09: any member edits a SHARED expense, only the owner a PERSONAL one (partner: not found).
        if (ownerUserId != null && !ownerUserId.equals(editor.value())) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_NOT_FOUND, "The expense was not found.");
        }
        amount.requirePositiveAtMost(ledger.maxExpense());
        if (!date.equals(expenseDate)) {
            requireDateInWindow(date, LocalDate.ofInstant(clock.instant(), ledger.timezone()));
        }
        if (sharing != sharingType() && !editor.value().equals(paidByUserId)) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_SHARING_CHANGE_FORBIDDEN,
                    "Only the payer can switch an expense between SHARED and PERSONAL.");
        }
        UUID newOwner = sharing == SharingType.PERSONAL ? editor.value() : null;
        if (newOwner != null && (!paidBy.value().equals(newOwner) || !createdBy.equals(newOwner))) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_PERSONAL_PAYER_MISMATCH,
                    "A personal expense must be paid by its creator.");
        }
        requireConsistentItems(amount, newItems, ledger.maxExpense());
        requireRefundInvariants(sharing, date, amount, refunds);

        ExpenseSnapshot before = snapshot();
        this.amountMinor = amount.minorUnits();
        this.expenseDate = date;
        this.paidByUserId = paidBy.value();
        this.ownerUserId = newOwner;
        applyMerchant(merchant);
        this.note = validNote(newNote);
        List<ExpenseSnapshot.Item> wanted = newItems.stream().map(i -> new ExpenseSnapshot.Item(i.categoryId(),
                i.amount().minorUnits(), validLabel(i.label()))).toList();
        if (!wanted.equals(before.items())) {
            items.clear();
            int position = 1;
            for (ExpenseSnapshot.Item item : wanted) {
                items.add(new ExpenseItem(UuidV7.generate(clock), householdId, position++, item.categoryId(),
                        item.amountMinor(), item.label()));
            }
        }
        if (before.equals(snapshot())) {
            return false;
        }
        // updatedAt strictly increases, so that every real change makes the row dirty and increments the version
        // (BR-EXP-12) even when only the items changed, or the clock did not advance.
        Instant now = clock.instant();
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1_000);
        this.updatedBy = editor.value();
        return true;
    }

    /**
     * BR-EXP-11 logical deletion, called by the use case with the row locked and the caller's visibility already
     * checked. BR-EXP-03: an expense with live refunds cannot be deleted ({@code hasLiveRefunds} is read under the
     * same lock, refund creation locking this row too). The version is incremented at flush (ETag).
     */
    public void delete(UserId actor, Clock clock, boolean hasLiveRefunds) {
        Objects.requireNonNull(actor, "actor");
        if (deletedAt != null) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_NOT_FOUND, "The expense was not found.");
        }
        if (hasLiveRefunds) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_HAS_LIVE_REFUNDS,
                    "An expense with refunds cannot be deleted: delete its refunds first.");
        }
        Instant now = clock.instant();
        this.deletedAt = now;
        this.deletedBy = actor.value();
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1_000);
        this.updatedBy = actor.value();
    }

    /**
     * BR-EXP-11 restore: only within {@value #RESTORE_WINDOW_DAYS} days of the deletion (the purge removes rows
     * deleted strictly more than 90 days ago). Outside the window the expense is reported as not found, as the
     * purge may already have removed it. BR-EXP-03: restoring a linked refund re-checks it against its original,
     * read under the lock on the original ({@code original} is {@code null} when the original is deleted;
     * {@code otherRefundsMinor} is the sum of the other live refunds of the original).
     */
    public void restore(UserId actor, Clock clock, LedgerProfile ledger, @Nullable Expense original,
            long otherRefundsMinor) {
        Objects.requireNonNull(actor, "actor");
        Instant now = clock.instant();
        if (deletedAt == null || now.isAfter(deletedAt.plus(RESTORE_WINDOW_DAYS, ChronoUnit.DAYS))) {
            throw new ApplicationException(ExpenseErrorCode.EXPENSE_NOT_FOUND, "The expense was not found.");
        }
        if (refundOfExpenseId != null) {
            if (original == null) {
                throw new ApplicationException(ExpenseErrorCode.EXPENSE_REFUND_ORIGINAL_INVALID,
                        "The original expense of this refund is deleted: restore it first.");
            }
            original.requireRefundable(new HouseholdId(householdId), sharingType(), expenseDate,
                    Money.ofMinor(amountMinor, ledger.currency(), ledger.decimals()),
                    Money.ofMinor(otherRefundsMinor, ledger.currency(), ledger.decimals()));
        }
        this.deletedAt = null;
        this.deletedBy = null;
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plusNanos(1_000);
        this.updatedBy = actor.value();
    }

    /** BR-EXP-03 for an edit: a linked refund stays within its original; an original keeps its refunds valid. */
    private void requireRefundInvariants(SharingType sharing, LocalDate date, Money amount, RefundState refunds) {
        if (refunds.original() != null) {
            refunds.original().requireRefundable(new HouseholdId(householdId), sharing, date, amount,
                    Money.ofMinor(refunds.otherRefundsMinor(), amount.currency(), amount.amount().scale()));
        }
        if (refunds.ownRefundsMinor() > 0) {
            if (amount.minorUnits() < refunds.ownRefundsMinor()) {
                throw new ApplicationException(ExpenseErrorCode.EXPENSE_REFUND_EXCEEDS_ORIGINAL,
                        "The refunds of an expense cannot exceed its amount.");
            }
            if (sharing != sharingType()) {
                throw new ApplicationException(ExpenseErrorCode.EXPENSE_REFUND_VISIBILITY_MISMATCH,
                        "A refund has the same sharing type as its original expense.");
            }
            if (refunds.earliestOwnRefund() != null && refunds.earliestOwnRefund().isBefore(date)) {
                throw new ApplicationException(ExpenseErrorCode.EXPENSE_REFUND_DATE_BEFORE_ORIGINAL,
                        "A refund cannot be dated before its original expense.");
            }
        }
    }

    private static @Nullable String normalisedLabel(@Nullable String label) {
        return label == null || label.isBlank() ? null : label.strip();
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

    public @Nullable Instant deletedAt() {
        return deletedAt;
    }

    public @Nullable UUID deletedBy() {
        return deletedBy;
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
