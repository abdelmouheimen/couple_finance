package com.couplefinance.receipt.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import com.couplefinance.expense.api.ReceiptExpenses;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptAuditLog;
import com.couplefinance.receipt.domain.ReceiptConfirmationPolicy;
import com.couplefinance.receipt.domain.ReceiptDraft;
import com.couplefinance.receipt.domain.ReceiptErrorCode;
import com.couplefinance.receipt.domain.ReceiptRepository;
import com.couplefinance.receipt.domain.ReceiptReviewStore;
import com.couplefinance.receipt.domain.ReceiptState;
import com.couplefinance.receipt.domain.ReceiptStatus;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.idempotency.IdempotencyKey;
import com.couplefinance.shared.idempotency.IdempotencyService;
import com.couplefinance.shared.idempotency.IdempotentResponse;
import com.couplefinance.shared.idempotency.IdempotentResult;
import com.couplefinance.shared.idempotency.RequestHash;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Turns a reviewed receipt into evidence of an expense, exactly once (architecture.md section 6.2 step 4,
 * BR-RCP-02, BR-RCP-10, BR-RCP-15): either a NEW expense created from the values the member submitted and
 * acknowledged, or an ATTACHment to an existing expense. In ONE transaction: receipt row lock → guards → conditional
 * status update → confirmation snapshot → expense through {@code expense.api} → audit. If the expense is refused
 * (any rule of a manual expense), everything rolls back and the receipt stays open.
 *
 * <p>The AI draft is never the source of a confirmed value: the expense amount and date are the request's, which
 * the member explicitly acknowledged. Deliberately not {@code @Transactional}: the idempotency claim commits
 * independently (BR-EXP-13).
 */
@Service
public class ConfirmReceiptService {

    static final String CONFIRM = "receipt.confirm";
    static final String ATTACH = "receipt.attach";
    private static final Logger log = LoggerFactory.getLogger(ConfirmReceiptService.class);

    private final CurrentHousehold currentHousehold;
    private final ReceiptRepository receipts;
    private final ReceiptReviewStore store;
    private final ReceiptExpenses expenses;
    private final ReceiptAuditLog audit;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    ConfirmReceiptService(CurrentHousehold currentHousehold, ReceiptRepository receipts, ReceiptReviewStore store,
            ReceiptExpenses expenses, ReceiptAuditLog audit, IdempotencyService idempotency,
            TransactionTemplate transaction, JsonMapper jsonMapper, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.receipts = receipts;
        this.store = store;
        this.expenses = expenses;
        this.audit = audit;
        this.idempotency = idempotency;
        this.transaction = transaction;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /**
     * The values of the expense to create, as submitted by the member.
     *
     * @param version           the receipt version the member reviewed
     * @param acknowledgedTotal must equal {@code amount} (BR-RCP-10)
     * @param acknowledgedDate  must equal {@code date} (BR-RCP-10)
     * @param resolutions       field → value chosen for every draft field whose status is not OK
     */
    public record Confirmation(long version, boolean refund, Money amount, LocalDate date, UUID categoryId,
            UUID paidByUserId, boolean personal, @Nullable String merchant, @Nullable String note,
            Money acknowledgedTotal, LocalDate acknowledgedDate, Map<String, String> resolutions) {
        public Confirmation {
            resolutions = Map.copyOf(resolutions);
        }
    }

    /** The link created. It is also the body stored for idempotent replay. */
    public record Linked(UUID receiptId, UUID expenseId, ReceiptState state) {
    }

    /** @param replayed whether this is the stored answer of an earlier identical request */
    public record Result(Linked linked, boolean replayed) {
    }

    public Result confirm(UUID receiptId, Confirmation confirmation, Optional<IdempotencyKey> key) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        return idempotent(context, key, CONFIRM, hash(receiptId, confirmation), HttpStatus.CREATED,
                recorded -> transaction.execute(status -> {
                    Receipt receipt = lock(context, receiptId);
                    ReceiptConfirmationPolicy.requireLinkable(receipt, confirmation.version());
                    Optional<ReceiptDraft> draft = store.draft(receipt.id(), context.householdId());
                    ReceiptConfirmationPolicy.requireReviewed(draft, confirmation.amount(), confirmation.date(),
                            confirmation.acknowledgedTotal(), confirmation.acknowledgedDate(),
                            confirmation.resolutions());
                    Instant now = clock.instant();
                    link(context, receipt, ReceiptStatus.CONFIRMED, confirmation.version(), now);
                    store.record(new ReceiptReviewStore.Confirmation(receipt.id(), context.householdId(),
                            "NEW_EXPENSE", context.userId(), now, confirmation.acknowledgedTotal().minorUnits(),
                            confirmation.acknowledgedDate(), confirmation.resolutions(), recorded));
                    // Same validation, audit and event as a manual expense; a refusal rolls everything back.
                    UUID expense = expenses.createFromReceipt(new ReceiptExpenses.NewReceiptExpense(receipt.id(),
                            confirmation.refund(), confirmation.amount(), confirmation.date(),
                            confirmation.categoryId(), confirmation.paidByUserId(), confirmation.personal(),
                            confirmation.merchant(), confirmation.note()));
                    audit.linked(receipt, context.userId(), "CONFIRM");
                    log.info("Receipt confirmed: receipt={} expense={}", receipt.id(), expense);
                    return new Linked(receipt.id(), expense, ReceiptState.CONFIRMED);
                }));
    }

    /** Attaches the receipt to an existing expense the caller can edit (BR-RCP-14): nothing of the expense changes. */
    public Result attach(UUID receiptId, UUID expenseId, long version, Optional<IdempotencyKey> key) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        byte[] hash = RequestHash.forOperation(ATTACH).field("receipt", receiptId.toString())
                .field("expense", expenseId.toString()).field("version", Long.toString(version)).build();
        return idempotent(context, key, ATTACH, hash, HttpStatus.OK, recorded -> transaction.execute(status -> {
            Receipt receipt = lock(context, receiptId);
            ReceiptConfirmationPolicy.requireLinkable(receipt, version);
            Instant now = clock.instant();
            link(context, receipt, ReceiptStatus.ATTACHED, version, now);
            store.record(new ReceiptReviewStore.Confirmation(receipt.id(), context.householdId(), "ATTACH",
                    context.userId(), now, null, null, Map.of(), recorded));
            expenses.attachReceipt(expenseId, receipt.id());
            audit.linked(receipt, context.userId(), "ATTACH");
            log.info("Receipt attached: receipt={} expense={}", receipt.id(), expenseId);
            return new Linked(receipt.id(), expenseId, ReceiptState.ATTACHED);
        }));
    }

    /** Only the uploader reaches a receipt here (BR-RCP-16): anyone else gets the same 404 as an unknown id. */
    private Receipt lock(HouseholdContext context, UUID receiptId) {
        return receipts.lockUploaded(receiptId, context.householdId(), context.userId())
                .orElseThrow(() -> new ApplicationException(ReceiptErrorCode.RECEIPT_NOT_FOUND,
                        "Receipt not found."));
    }

    private void link(HouseholdContext context, Receipt receipt, ReceiptStatus status, long version, Instant now) {
        if (!store.link(receipt.id(), context.householdId(), status, version, now)) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_ALREADY_CONFIRMED,
                    "This receipt is already linked to an expense.");
        }
    }

    /** With a key: claimed first, replayed for the same request. Without: a server-side key is recorded. */
    private Result idempotent(HouseholdContext context, Optional<IdempotencyKey> key, String operation, byte[] hash,
            HttpStatus status, Function<String, Linked> action) {
        if (key.isEmpty()) {
            return new Result(action.apply("server-" + UUID.randomUUID()), false);
        }
        IdempotentResult result = idempotency.execute(context.userId().value(), key.get(), operation, hash,
                () -> new IdempotentResponse(status.value(),
                        jsonMapper.writeValueAsString(action.apply(key.get().value()))));
        return new Result(jsonMapper.readValue(result.response().jsonBody(), Linked.class), result.replayed());
    }

    /** Every field that changes the outcome, in canonical text form (the user is the key scope). */
    private static byte[] hash(UUID receiptId, Confirmation c) {
        RequestHash.Builder hash = RequestHash.forOperation(CONFIRM)
                .field("receipt", receiptId.toString())
                .field("version", Long.toString(c.version()))
                .field("refund", Boolean.toString(c.refund()))
                .field("amount", c.amount().toDecimalString())
                .field("currency", c.amount().currency().value())
                .field("date", c.date().toString())
                .field("category", c.categoryId().toString())
                .field("paidBy", c.paidByUserId().toString())
                .field("personal", Boolean.toString(c.personal()))
                .field("merchant", c.merchant())
                .field("note", c.note())
                .field("ackTotal", c.acknowledgedTotal().toDecimalString())
                .field("ackCurrency", c.acknowledgedTotal().currency().value())
                .field("ackDate", c.acknowledgedDate().toString());
        new TreeMap<>(c.resolutions()).forEach((field, value) -> hash.field("resolution." + field, value));
        return hash.build();
    }
}
