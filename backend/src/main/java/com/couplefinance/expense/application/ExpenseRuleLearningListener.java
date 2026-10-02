package com.couplefinance.expense.application;

import java.util.List;

import com.couplefinance.categorization.api.LearnedSave;
import com.couplefinance.categorization.api.MerchantRuleLearning;
import com.couplefinance.categorization.api.NormalisedMerchant;
import com.couplefinance.expense.api.ExpenseCreated;
import com.couplefinance.expense.domain.Expense;
import com.couplefinance.expense.domain.ExpenseItem;
import com.couplefinance.expense.domain.ExpenseKind;
import com.couplefinance.expense.domain.ExpenseRepository;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * BR-CAT-05: feeds merchant-rule learning when an expense is saved. Runs after the expense transaction commits
 * (learning opens its own transaction) and re-reads the expense (household-scoped) instead of trusting the event payload.
 * Learning is idempotent per expense id on the categorization side, so a re-delivery changes nothing.
 *
 * <p>Only an {@code EXPENSE} with a merchant and exactly one item is learned from: with several items no single
 * category stands for the merchant, and a refund is not a purchase choice. The sharing type is taken from the
 * stored expense: a PERSONAL expense only ever feeds its owner's private rule.
 *
 * <p>A failure here must never fail the already committed save: it is logged (ids only) and skipped.
 */
@Component
class ExpenseRuleLearningListener {

    private static final Logger LOG = LoggerFactory.getLogger(ExpenseRuleLearningListener.class);

    private final ExpenseRepository expenses;
    private final MerchantRuleLearning learning;

    ExpenseRuleLearningListener(ExpenseRepository expenses, MerchantRuleLearning learning) {
        this.expenses = expenses;
        this.learning = learning;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ExpenseCreated event) {
        try {
            expenses.findLiveByIdAndHouseholdId(event.expenseId(), event.householdId())
                    .flatMap(ExpenseRuleLearningListener::learnedSave)
                    .ifPresent(learning::learn);
        } catch (RuntimeException e) {
            LOG.warn("Merchant rule learning failed for expense {}: {}", event.expenseId(), e.getClass().getSimpleName());
        }
    }

    private static java.util.Optional<LearnedSave> learnedSave(Expense expense) {
        List<ExpenseItem> items = expense.items();
        if (expense.kind() != ExpenseKind.EXPENSE || expense.merchantKey() == null || items.size() != 1
                || expense.merchantNormaliserVersion() == null) {
            return java.util.Optional.empty();
        }
        UserId owner = expense.ownerUserId() == null ? null : new UserId(expense.ownerUserId());
        return java.util.Optional.of(new LearnedSave(new HouseholdId(expense.householdId()), owner,
                new NormalisedMerchant(expense.merchantKey(), expense.merchantNormaliserVersion()),
                items.get(0).categoryId(), expense.id()));
    }
}
