package com.couplefinance.receipt.application;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.categorization.api.CategoryCatalogue;
import com.couplefinance.categorization.api.CategorySuggestion;
import com.couplefinance.categorization.api.CategorySuggestions;
import com.couplefinance.categorization.api.RuleScope;
import com.couplefinance.expense.api.ReceiptExpenses;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.receipt.application.ReceiptReviewView.Duplicate;
import com.couplefinance.receipt.application.ReceiptReviewView.DuplicateReason;
import com.couplefinance.receipt.domain.DuplicateSignals;
import com.couplefinance.receipt.domain.ProposedKind;
import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptConfirmationPolicy;
import com.couplefinance.receipt.domain.ReceiptDraft;
import com.couplefinance.receipt.domain.ReceiptErrorCode;
import com.couplefinance.receipt.domain.ReceiptRepository;
import com.couplefinance.receipt.domain.ReceiptReviewStore;
import com.couplefinance.receipt.domain.ReceiptState;
import com.couplefinance.receipt.domain.ReceiptStatus;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The review of an open receipt by its uploader (F5, BR-RCP-10, BR-RCP-14, BR-RCP-16): the draft as proposals, a
 * rule-based category suggestion and duplicate warnings. Read-only and deterministic: no AI call is made here, and
 * the category is derived from the merchant name alone, through the household's own rules (BR-CAT-04).
 */
@Service
public class ReceiptReviewService {

    private final CurrentHousehold currentHousehold;
    private final ReceiptRepository receipts;
    private final ReceiptReviewStore store;
    private final HouseholdLedgerRules ledgers;
    private final CategorySuggestions suggestions;
    private final CategoryCatalogue categories;
    private final ReceiptExpenses expenses;

    ReceiptReviewService(CurrentHousehold currentHousehold, ReceiptRepository receipts, ReceiptReviewStore store,
            HouseholdLedgerRules ledgers, CategorySuggestions suggestions, CategoryCatalogue categories,
            ReceiptExpenses expenses) {
        this.currentHousehold = currentHousehold;
        this.receipts = receipts;
        this.store = store;
        this.ledgers = ledgers;
        this.suggestions = suggestions;
        this.categories = categories;
        this.expenses = expenses;
    }

    @Transactional(readOnly = true)
    public ReceiptReviewView review(UUID id) {
        HouseholdContext context = currentHousehold.currentHousehold();
        // findVisible only returns an open draft of the caller: the partner and other households get 404.
        Receipt receipt = receipts.findVisible(id, context.householdId(), context.userId())
                .orElseThrow(() -> new ApplicationException(ReceiptErrorCode.RECEIPT_NOT_FOUND,
                        "Receipt not found."));
        LedgerProfile ledger = ledgers.profile(context.householdId());
        Optional<ReceiptDraft> draft = store.draft(receipt.id(), context.householdId());
        Map<UUID, UUID> links = expenses.visibleLinks(context.householdId(), context.userId());

        Optional<String> merchant = draft.map(d -> d.fields().get("merchant")).map(ReceiptDraft.Field::raw)
                .map(String::strip).filter(raw -> !raw.isEmpty());
        Optional<Money> total = draft.flatMap(ReceiptDraft::totalMinor)
                .map(minor -> Money.ofMinor(Math.abs(minor), ledger.currency(), ledger.decimals()));
        Optional<LocalDate> date = draft.flatMap(ReceiptDraft::purchaseDate);

        Map<String, ReceiptReviewView.FieldView> fields = new LinkedHashMap<>();
        draft.ifPresent(d -> ReceiptConfirmationPolicy.REVIEW_FIELDS.forEach(name -> {
            ReceiptDraft.Field field = d.fields().get(name);
            if (field != null) {
                fields.put(name, new ReceiptReviewView.FieldView(field.raw(), field.candidates(), field.status(),
                        field.confidence()));
            }
        }));
        List<ReceiptReviewView.LineView> lines = draft.map(d -> d.lines().stream()
                .map(line -> new ReceiptReviewView.LineView(line.position(), line.type().name(), line.description(),
                        line.amountRaw(), line.amountMinor() == null ? null
                                : Money.ofMinor(line.amountMinor(), ledger.currency(), ledger.decimals()),
                        line.amountStatus())).toList()).orElse(List.of());

        return new ReceiptReviewView(receipt.id(), receipt.version(),
                ReceiptState.of(receipt.status(), receipt.analysisStatus()), draft.isPresent(),
                draft.map(d -> d.documentType().name()).orElse(null),
                draft.map(d -> d.proposedKind() == ProposedKind.REFUND).orElse(false), ledger.currency().value(),
                merchant.orElse(null), date.orElse(null), total.orElse(null),
                draft.flatMap(ReceiptDraft::detectedCurrency).orElse(null), fields,
                ReceiptConfirmationPolicy.unresolved(draft, Map.of()), lines,
                draft.map(d -> d.findings().stream().sorted().toList()).orElse(List.of()),
                merchant.flatMap(raw -> suggest(context, raw)).orElse(null),
                duplicates(context, receipt, draft, total, date, links, ledger.currency().value()));
    }

    /** BR-CAT-04 through the household's rules; a category the household cannot use is never suggested. */
    private Optional<ReceiptReviewView.SuggestedCategory> suggest(HouseholdContext context, String merchant) {
        CategorySuggestion suggestion = suggestions.suggest(context.householdId(), context.userId(), merchant,
                RuleScope.SHARED);
        if (!categories.unusableCategories(context.householdId(), List.of(suggestion.categoryId())).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ReceiptReviewView.SuggestedCategory(suggestion.categoryId(),
                suggestion.source().name()));
    }

    /**
     * BR-RCP-14, limited to what the viewer may see: their own drafts, receipts linked to an expense they can see,
     * and expenses they can see. The partner's drafts and PERSONAL expenses never produce a warning.
     */
    private List<Duplicate> duplicates(HouseholdContext context, Receipt receipt, Optional<ReceiptDraft> draft,
            Optional<Money> total, Optional<LocalDate> date, Map<UUID, UUID> links, String householdCurrency) {
        List<Duplicate> duplicates = new ArrayList<>();
        Map<UUID, DuplicateReason> related = new LinkedHashMap<>();
        store.sameFile(receipt).forEach(r -> visible(context, r, links)
                .ifPresent(id -> related.putIfAbsent(id, DuplicateReason.SAME_FILE)));
        store.similarImage(receipt, DuplicateSignals.PERCEPTUAL_MAX_DISTANCE).forEach(r -> visible(context, r, links)
                .ifPresent(id -> related.putIfAbsent(id, DuplicateReason.SIMILAR_IMAGE)));
        related.forEach((id, reason) -> duplicates.add(new Duplicate(reason, id, links.get(id), false)));

        // A total printed in another currency is not comparable with the ledger's amounts (BR-RCP-09).
        boolean sameCurrency = draft.flatMap(ReceiptDraft::detectedCurrency)
                .map(householdCurrency::equals).orElse(true);
        Optional<String> merchantKey = draft.flatMap(ReceiptDraft::merchantKey);
        if (total.isPresent() && date.isPresent() && merchantKey.isPresent() && sameCurrency) {
            for (ReceiptExpenses.SimilarExpense expense : expenses.similar(context.householdId(), context.userId(),
                    total.get(), date.get())) {
                boolean known = duplicates.stream().anyMatch(d -> expense.expenseId().equals(d.expenseId()));
                if (!known && expense.merchantKey() != null
                        && DuplicateSignals.sameMerchant(merchantKey.get(), expense.merchantKey())) {
                    duplicates.add(new Duplicate(DuplicateReason.MATCHING_EXPENSE, null, expense.expenseId(),
                            !expense.hasReceipt()));
                }
            }
        }
        return duplicates;
    }

    private static Optional<UUID> visible(HouseholdContext context, ReceiptReviewStore.RelatedReceipt related,
            Map<UUID, UUID> links) {
        boolean ownDraft = related.status() == ReceiptStatus.OPEN
                && related.uploadedBy().equals(context.userId());
        return ownDraft || links.containsKey(related.id()) ? Optional.of(related.id()) : Optional.empty();
    }
}
