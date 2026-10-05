package com.couplefinance.budget.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.couplefinance.budget.api.BudgetCreated;
import com.couplefinance.budget.api.BudgetUpdated;
import com.couplefinance.budget.domain.Budget;
import com.couplefinance.budget.domain.BudgetAuditLog;
import com.couplefinance.budget.domain.BudgetCategoryLimits;
import com.couplefinance.budget.domain.BudgetCategoryLimits.Line;
import com.couplefinance.budget.domain.BudgetCopy;
import com.couplefinance.budget.domain.BudgetErrorCode;
import com.couplefinance.budget.domain.BudgetRepository;
import com.couplefinance.budget.domain.BudgetStore;
import com.couplefinance.budget.domain.CategoryLimit;
import com.couplefinance.budget.domain.CategoryLimits;
import com.couplefinance.budget.domain.LimitConsumption;
import com.couplefinance.categorization.api.CategoryCatalogue;
import com.couplefinance.expense.api.SpendingGrouping;
import com.couplefinance.expense.api.SpendingQuery;
import com.couplefinance.expense.api.SpendingScope;
import com.couplefinance.household.api.BudgetPeriod;
import com.couplefinance.household.api.BudgetPeriods;
import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.household.api.HouseholdLedgerRules;
import com.couplefinance.household.api.LedgerProfile;
import com.couplefinance.shared.concurrency.VersionETag;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.CurrencyCode;
import com.couplefinance.shared.money.Money;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Use cases on the budget of a period (BR-BUD-01, BR-BUD-02, BR-BUD-04, BR-BUD-07). The household and the actor
 * come from the authenticated user; every lookup is scoped by that household, so another household's budget never
 * exists for the caller. The write is ONE transaction - budget row, category lines, audit entries and event - with
 * no external call inside (the category check is an in-process read of the categorization module).
 */
@Service
public class BudgetService {

    private final CurrentHousehold currentHousehold;
    private final BudgetPeriods periods;
    private final HouseholdLedgerRules ledgerRules;
    private final BudgetRepository budgets;
    private final BudgetStore store;
    private final BudgetCategoryLimits lines;
    private final CategoryCatalogue categories;
    private final BudgetAuditLog audit;
    private final ApplicationEventPublisher events;
    private final SpendingQuery spending;
    private final Clock clock;

    BudgetService(CurrentHousehold currentHousehold, BudgetPeriods periods, HouseholdLedgerRules ledgerRules,
            BudgetRepository budgets, BudgetStore store, BudgetCategoryLimits lines, CategoryCatalogue categories,
            BudgetAuditLog audit, ApplicationEventPublisher events, SpendingQuery spending, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.periods = periods;
        this.ledgerRules = ledgerRules;
        this.budgets = budgets;
        this.store = store;
        this.lines = lines;
        this.categories = categories;
        this.audit = audit;
        this.events = events;
        this.spending = spending;
        this.clock = clock;
    }

    /** Reads the budget of the period starting at {@code periodStart}; allowed to archive readers. */
    @Transactional(readOnly = true)
    public BudgetView get(LocalDate periodStart) {
        HouseholdContext context = currentHousehold.currentHousehold();
        requirePeriod(context, periodStart);
        UUID household = context.householdId().value();
        Budget budget = budgets.findByPeriod(household, periodStart).orElseThrow(
                () -> new ApplicationException(BudgetErrorCode.BUDGET_NOT_FOUND,
                        "There is no budget for this period."));
        int decimals = ledgerRules.profile(context.householdId()).decimals();
        List<Line> stored = lines.findByBudget(household, budget.id());
        return view(budget, decimals, overallConsumption(context, budget, decimals),
                categoryConsumption(context, budget, decimals, stored), stored);
    }

    /**
     * BR-BUD-03/05/06: household spending of the period, read only through the spending query API; computed on
     * read, never stored. PERSONAL spending is excluded by the HOUSEHOLD scope.
     */
    private @Nullable LimitConsumption overallConsumption(HouseholdContext context, Budget budget, int decimals) {
        Long limit = budget.overallLimitMinor();
        if (limit == null) {
            return null;
        }
        Money consumed = spending.spending(context, SpendingScope.HOUSEHOLD, budget.periodStart(),
                budget.periodEnd(), Set.of()).total();
        return LimitConsumption.of(Money.ofMinor(limit, budget.currency(), decimals), consumed);
    }

    /**
     * BR-BUD-03/05/06 per category limit: household spending of the period restricted to the items of that
     * category, read through the same spending query API in ONE grouped query; computed on read, never stored.
     * A category without any spending in the period consumes zero.
     */
    private Map<UUID, LimitConsumption> categoryConsumption(HouseholdContext context, Budget budget, int decimals,
            List<Line> stored) {
        if (stored.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Money> spent = new HashMap<>();
        spending.spending(context, SpendingScope.HOUSEHOLD, budget.periodStart(), budget.periodEnd(),
                Set.of(SpendingGrouping.CATEGORY)).byCategory()
                .forEach(category -> spent.put(category.categoryId(), category.total()));
        Map<UUID, LimitConsumption> consumption = new LinkedHashMap<>();
        for (Line line : stored) {
            Money limit = Money.ofMinor(line.limitMinor(), budget.currency(), decimals);
            Money consumed = spent.getOrDefault(line.categoryId(), Money.ofMinor(0, budget.currency(), decimals));
            consumption.put(line.categoryId(), LimitConsumption.of(limit, consumed));
        }
        return consumption;
    }

    /**
     * Creates the budget of the period (no {@code If-Match}) or updates its limits ({@code If-Match} required:
     * 428 when absent, 412 when stale). The category limits, when given, replace the existing ones.
     */
    @Transactional
    public SetBudgetResult set(SetBudgetCommand command) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        BudgetPeriod period = requirePeriod(context, command.periodStart());
        UUID household = context.householdId().value();
        LedgerProfile ledger = ledgerRules.profile(context.householdId());
        // Request validation first: an invalid body is a 400 whatever the state of the budget (BR-BUD-01/02).
        if (command.overallLimit() != null) {
            Budget.validLimit(command.overallLimit(), ledger.currency(), ledger.decimals());
        }
        List<CategoryLimit> requested = command.categoryLimits();
        if (requested != null) {
            CategoryLimits.validate(requested, ledger.currency(), ledger.decimals());
            Budget.requireAtLeastOneLimit(command.overallLimit(), requested.size());
        }

        Budget existing = budgets.lockByPeriod(household, period.start()).orElse(null);
        if (existing == null) {
            return create(context, period, ledger, command);
        }
        List<Line> current = lines.findByBudget(household, existing.id());
        int resulting = requested == null ? current.size() : requested.size();
        Budget.requireAtLeastOneLimit(command.overallLimit(), resulting); // BR-BUD-02, before the precondition
        VersionETag.requireMatch(VersionETag.requireIfMatch(command.ifMatch()), existing.version());
        if (requested != null) {
            Set<UUID> kept = new HashSet<>();
            current.forEach(line -> kept.add(line.categoryId()));
            requireUsableCategories(context, requested, kept);
        }

        Long before = existing.overallLimitMinor();
        boolean overallChanged = existing.setOverallLimit(command.overallLimit(), resulting, ledger.decimals(),
                clock, context.userId());
        boolean linesChanged = requested != null && applyLines(existing, current, requested, context);
        if (!overallChanged && !linesChanged) {
            return new SetBudgetResult(view(existing, ledger.decimals(), null, Map.of(), current), false);
        }
        if (linesChanged && !overallChanged) {
            existing.touch(clock, context.userId());
        }
        Budget saved = budgets.saveAndFlush(existing);
        if (overallChanged) {
            audit.overallLimitChanged(saved, before, context.userId());
        }
        List<Line> now = linesChanged ? lines.findByBudget(household, saved.id()) : current;
        if (linesChanged) {
            audit.categoryLimitsChanged(saved, minorByCategory(current), minorByCategory(now), context.userId());
        }
        events.publishEvent(new BudgetUpdated(saved.id(), saved.householdId(), saved.periodStart(),
                saved.updatedAt()));
        return new SetBudgetResult(view(saved, ledger.decimals(), null, Map.of(), now), false);
    }

    /**
     * BR-BUD-02: explicit copy of the budget of the period immediately preceding {@code periodStart} into that
     * period, as a starting point. Overall limit and category limits are copied, lines of since-archived
     * categories are omitted (BR-CAT-03), nothing else is carried over (no rollover), nothing is ever copied
     * automatically. Never overwrites: a target that already has a budget is a 409 (BR-BUD-01). One transaction
     * covers the budget, its lines, the audit entry and the event.
     */
    @Transactional
    public BudgetView copyPrevious(LocalDate periodStart) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable(); // BR-HH-10
        BudgetPeriod period = requirePeriod(context, periodStart);
        UUID household = context.householdId().value();
        LedgerProfile ledger = ledgerRules.profile(context.householdId());

        if (budgets.lockByPeriod(household, period.start()).isPresent()) {
            throw new ApplicationException(BudgetErrorCode.BUDGET_ALREADY_EXISTS,
                    "The period already has a budget; it is never overwritten by a copy.");
        }
        Budget source = periods.findPeriodContaining(context, periodStart.minusDays(1))
                .flatMap(previous -> budgets.lockByPeriod(household, previous.start()))
                .orElseThrow(() -> new ApplicationException(BudgetErrorCode.PREVIOUS_BUDGET_NOT_FOUND,
                        "The preceding period has no budget to copy."));
        List<Line> sourceLines = lines.findByBudget(household, source.id());
        Set<UUID> unusable = sourceLines.isEmpty() ? Set.of()
                : categories.unusableCategories(context.householdId(),
                        sourceLines.stream().map(Line::categoryId).toList());
        List<Line> copied = BudgetCopy.copiedLines(sourceLines, unusable);

        // BR-BUD-02: a copy left without any limit is rejected by the aggregate.
        Budget budget = Budget.copyOf(source, context.householdId(), period, ledger.decimals(), clock,
                copied.size(), context.userId());
        if (!store.insertIfAbsent(budget)) {
            // A concurrent request created the target period's budget first (uq_budget_period).
            throw new ApplicationException(BudgetErrorCode.BUDGET_ALREADY_EXISTS,
                    "The period already has a budget; it is never overwritten by a copy.");
        }
        Budget saved = budgets.findByPeriod(household, period.start()).orElseThrow();
        copied.forEach(line -> lines.insert(saved, line.categoryId(), line.limitMinor(), context.userId()));
        List<Line> stored = lines.findByBudget(household, saved.id());
        audit.created(saved, minorByCategory(stored), context.userId());
        events.publishEvent(new BudgetCreated(saved.id(), saved.householdId(), saved.periodStart(),
                saved.createdAt()));
        return view(saved, ledger.decimals(), null, Map.of(), stored);
    }

    private SetBudgetResult create(HouseholdContext context, BudgetPeriod period, LedgerProfile ledger,
            SetBudgetCommand command) {
        if (command.ifMatch() != null && !command.ifMatch().isBlank()) {
            // If-Match names a version of a budget that does not exist: the precondition fails (RFC 9110).
            throw VersionETag.versionConflict();
        }
        List<CategoryLimit> requested = command.categoryLimits() == null ? List.of() : command.categoryLimits();
        Budget budget = Budget.create(context.householdId(), period, ledger.currency(), ledger.decimals(), clock,
                command.overallLimit(), requested.size(), context.userId());
        requireUsableCategories(context, requested, Set.of());
        if (!store.insertIfAbsent(budget)) {
            // A concurrent request created the budget first (uq_budget_period): this caller did not hold its
            // version, so its "create" precondition no longer holds.
            throw VersionETag.versionConflict();
        }
        Budget saved = budgets.findByPeriod(context.householdId().value(), period.start()).orElseThrow();
        for (CategoryLimit line : requested) {
            lines.insert(saved, line.categoryId(), line.limit().minorUnits(), context.userId());
        }
        List<Line> stored = lines.findByBudget(saved.householdId(), saved.id());
        audit.created(saved, minorByCategory(stored), context.userId());
        events.publishEvent(new BudgetCreated(saved.id(), saved.householdId(), saved.periodStart(),
                saved.createdAt()));
        return new SetBudgetResult(view(saved, ledger.decimals(), null, Map.of(), stored), true);
    }

    /**
     * BR-CAT-03: a line on a category the budget does not hold yet must reference an active category visible to
     * the household; another household's category is indistinguishable from an unknown one (404). Lines already
     * in the budget stay valid even when their category has since been archived.
     */
    private void requireUsableCategories(HouseholdContext context, List<CategoryLimit> requested,
            Set<UUID> alreadyInBudget) {
        Set<UUID> added = new HashSet<>();
        requested.forEach(line -> {
            if (!alreadyInBudget.contains(line.categoryId())) {
                added.add(line.categoryId());
            }
        });
        if (added.isEmpty()) {
            return;
        }
        if (!categories.unknownCategories(context.householdId(), added).isEmpty()) {
            throw new ApplicationException(BudgetErrorCode.CATEGORY_NOT_FOUND, "The category was not found.");
        }
        if (!categories.unusableCategories(context.householdId(), added).isEmpty()) {
            throw new ApplicationException(BudgetErrorCode.BUDGET_CATEGORY_ARCHIVED,
                    "An archived category cannot get a new limit.");
        }
    }

    /** Brings the stored lines to {@code requested}; returns whether anything changed. */
    private boolean applyLines(Budget budget, List<Line> current, List<CategoryLimit> requested,
            HouseholdContext context) {
        Map<UUID, Long> wanted = new LinkedHashMap<>();
        requested.forEach(line -> wanted.put(line.categoryId(), line.limit().minorUnits()));
        boolean changed = false;
        Set<UUID> stored = new HashSet<>();
        for (Line line : current) {
            stored.add(line.categoryId());
            Long target = wanted.get(line.categoryId());
            if (target == null) {
                lines.delete(budget, line.categoryId());
                changed = true;
            } else if (target != line.limitMinor()) {
                lines.updateLimit(budget, line.categoryId(), target, context.userId());
                changed = true;
            }
        }
        for (Map.Entry<UUID, Long> entry : wanted.entrySet()) {
            if (!stored.contains(entry.getKey())) {
                lines.insert(budget, entry.getKey(), entry.getValue(), context.userId());
                changed = true;
            }
        }
        return changed;
    }

    private static Map<UUID, Long> minorByCategory(List<Line> stored) {
        Map<UUID, Long> minor = new LinkedHashMap<>();
        stored.forEach(line -> minor.put(line.categoryId(), line.limitMinor()));
        return minor;
    }

    private static BudgetView view(Budget budget, int decimals, @Nullable LimitConsumption consumption,
            Map<UUID, LimitConsumption> categoryConsumption, List<Line> stored) {
        CurrencyCode currency = budget.currency();
        List<CategoryLimit> limits = new ArrayList<>();
        stored.forEach(line -> limits.add(new CategoryLimit(line.categoryId(),
                Money.ofMinor(line.limitMinor(), currency, decimals))));
        Long overall = budget.overallLimitMinor();
        Money overallLimit = overall == null ? null : Money.ofMinor(overall, currency, decimals);
        return BudgetView.of(budget, decimals, consumption, List.copyOf(limits), categoryConsumption,
                CategoryLimits.warningFor(overallLimit, limits, currency, decimals));
    }

    /** BR-HH-07: the path date must be the start of an existing period of the caller's household calendar. */
    private BudgetPeriod requirePeriod(HouseholdContext context, LocalDate periodStart) {
        return periods.findPeriodContaining(context, periodStart).filter(p -> p.start().equals(periodStart))
                .orElseThrow(() -> new ApplicationException(BudgetErrorCode.BUDGET_PERIOD_NOT_FOUND,
                        "There is no budget period starting on this date."));
    }
}
