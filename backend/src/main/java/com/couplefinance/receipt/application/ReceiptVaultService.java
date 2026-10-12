package com.couplefinance.receipt.application;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.expense.api.ReceiptExpenses;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptAuditLog;
import com.couplefinance.receipt.domain.ReceiptErrorCode;
import com.couplefinance.receipt.domain.ReceiptPage;
import com.couplefinance.receipt.domain.ReceiptRepository;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.pagination.CursorCodec;
import com.couplefinance.shared.pagination.CursorPage;
import com.couplefinance.shared.pagination.PageQuery;
import com.couplefinance.shared.pagination.PaginationErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The receipt vault (DOC-001): listing, reading, page access through short-lived presigned URLs and deletion.
 * Visibility is enforced here, server-side, through the scoped {@link ReceiptRepository}: a member sees their own
 * drafts and the receipts linked to an expense they can see (BR-RCP-16); anything else is a 404.
 */
@Service
public class ReceiptVaultService {

    /** security.md section 6: presigned GET URLs are valid 5 minutes and issued after the visibility check. */
    public static final Duration PAGE_URL_VALIDITY = Duration.ofMinutes(5);
    static final String LISTING = "receipts";
    private static final Logger log = LoggerFactory.getLogger(ReceiptVaultService.class);

    private final CurrentHousehold currentHousehold;
    private final ReceiptRepository receipts;
    private final ReceiptAuditLog audit;
    private final DocumentStorage storage;
    private final ReceiptExpenses expenses;
    private final CursorCodec cursors;
    private final TransactionTemplate transaction;
    private final Clock clock;

    ReceiptVaultService(CurrentHousehold currentHousehold, ReceiptRepository receipts, ReceiptAuditLog audit,
            DocumentStorage storage, ReceiptExpenses expenses, CursorCodec cursors, TransactionTemplate transaction,
            Clock clock) {
        this.expenses = expenses;
        this.currentHousehold = currentHousehold;
        this.receipts = receipts;
        this.audit = audit;
        this.storage = storage;
        this.cursors = cursors;
        this.transaction = transaction;
        this.clock = clock;
    }

    /** A presigned page URL and its expiry. */
    public record PageUrl(URI url, Instant expiresAt, String contentType) {
    }

    public CursorPage<ReceiptView> list(PageQuery query) {
        HouseholdContext context = currentHousehold.currentHousehold();
        String scope = LISTING + ":" + context.userId() + ":" + context.householdId();
        Instant afterCreatedAt = null;
        UUID afterId = null;
        if (query.cursor().isPresent()) {
            List<String> position = cursors.decode(scope, query.cursor().get());
            try {
                afterCreatedAt = Instant.parse(position.get(0));
                afterId = UUID.fromString(position.get(1));
            } catch (RuntimeException e) {
                throw new ApplicationException(PaginationErrorCode.INVALID_CURSOR,
                        "The cursor is invalid.", e);
            }
        }
        // A linked receipt follows the visibility of its expense (BR-RCP-16), resolved by the expense module.
        Map<UUID, UUID> links = expenses.visibleLinks(context.householdId(), context.userId());
        List<ReceiptView> fetched = receipts.listVisible(context.householdId(), context.userId(), links.keySet(),
                afterCreatedAt, afterId, query.fetchSize()).stream()
                .map(receipt -> ReceiptView.from(receipt, receipt.isDraft() ? null : links.get(receipt.id())))
                .toList();
        return CursorPage.of(fetched, query, r -> List.of(r.createdAt().toString(), r.id().toString()), cursors,
                scope);
    }

    public ReceiptView get(UUID id) {
        HouseholdContext context = currentHousehold.currentHousehold();
        Optional<Receipt> draft = receipts.findVisible(id, context.householdId(), context.userId());
        if (draft.isPresent()) {
            return ReceiptView.from(draft.get());
        }
        UUID expense = expenses.visibleExpenseOf(context.householdId(), context.userId(), id)
                .orElseThrow(ReceiptVaultService::notFound);
        return ReceiptView.from(receipts.findLinked(id, context.householdId())
                .orElseThrow(ReceiptVaultService::notFound), expense);
    }

    public PageUrl pageUrl(UUID id, int position) {
        HouseholdContext context = currentHousehold.currentHousehold();
        ReceiptPage page = visible(context, id).page(position)
                .orElseThrow(() -> new ApplicationException(ReceiptErrorCode.RECEIPT_PAGE_NOT_FOUND,
                        "This receipt has no such page."));
        Instant expiresAt = clock.instant().plus(PAGE_URL_VALIDITY);
        return new PageUrl(storage.presignedGet(page.storageKey(), PAGE_URL_VALIDITY), expiresAt,
                page.contentType());
    }

    /**
     * Deletes an open receipt of the caller: rows and audit in one transaction, then the stored objects (network
     * I/O, after commit). An object that cannot be deleted is left behind and logged by receipt id only.
     */
    public void delete(UUID id) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable();
        Receipt deleted = transaction.execute(status -> {
            Receipt receipt = receipts.lockVisible(id, context.householdId(), context.userId())
                    .or(() -> linked(context, id))
                    .orElseThrow(ReceiptVaultService::notFound);
            receipt.requireDeletable(); // BR-RCP-20: a linked receipt is removed from its expense, not here
            receipts.delete(receipt.id(), context.householdId());
            audit.deleted(receipt, context.userId());
            return receipt;
        });
        for (ReceiptPage page : deleted.pages()) {
            try {
                storage.delete(page.storageKey());
            } catch (RuntimeException e) {
                log.warn("Receipt object not deleted: receipt={} page={} ({})", deleted.id(), page.position(),
                        e.getClass().getSimpleName());
            }
        }
        log.info("Receipt deleted: receipt={}", deleted.id());
    }

    /** The caller's own draft, or a linked receipt whose expense the caller may see (BR-RCP-16). */
    private Receipt visible(HouseholdContext context, UUID id) {
        return receipts.findVisible(id, context.householdId(), context.userId())
                .or(() -> linked(context, id))
                .orElseThrow(ReceiptVaultService::notFound);
    }

    private Optional<Receipt> linked(HouseholdContext context, UUID id) {
        return expenses.visibleExpenseOf(context.householdId(), context.userId(), id)
                .flatMap(expense -> receipts.findLinked(id, context.householdId()));
    }

    private static ApplicationException notFound() {
        return new ApplicationException(ReceiptErrorCode.RECEIPT_NOT_FOUND, "Receipt not found.");
    }
}
