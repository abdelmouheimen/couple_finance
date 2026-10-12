import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { Fragment, useRef, useState } from "react";
import { RefreshControl, StyleSheet, View } from "react-native";
import { categoryVisual } from "@/features/expense/categoryVisual";
import { shiftBudgetPeriod } from "@/features/household/currentPeriod";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import type { components } from "@/shared/api/client";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { formatMoney, type Money } from "@/shared/money/money";
import {
    Button,
    Card,
    Divider,
    EmptyState,
    ErrorState,
    Icon,
    IconButton,
    MoneyText,
    ProgressBar,
    Screen,
    ScreenTitle,
    SectionTitle,
    Skeleton,
    Text,
    useColors,
    useToast,
} from "@/shared/ui";
import { radius, spacing } from "@/shared/ui/theme/tokens";
import { BudgetEditSheet } from "./BudgetEditSheet";
import {
    type Budget,
    budgetKey,
    CATEGORIES_KEY,
    copyPreviousBudget,
    fetchAllCategories,
    fetchBudget,
    setBudget,
} from "./budgetApi";
import { type BudgetForm, categoryName, toSetBudgetRequest } from "./budgetForm";
import { CategoryLimitRow } from "./CategoryLimitRow";
import { periodLabel, periodTitle } from "./periodLabel";

type ApiMoney = components["schemas"]["Money"];

function asMoney(m: ApiMoney | undefined): Money | null {
    return m?.amount !== undefined && m.currency !== undefined
        ? { amount: m.amount, currency: m.currency }
        : null;
}

/** Consumption exactly as returned by the API (BR-BUD-03/05/06, BR-MON-09): no arithmetic on device. */
function OverallCard({ budget }: { budget: Budget }) {
    const c = budget.overallConsumption;
    const limit = asMoney(budget.overallLimit);
    const remaining = asMoney(c?.remaining);
    const consumed = asMoney(c?.consumed);
    if (!(c && limit && remaining && consumed && c.status && c.percentage !== undefined)) {
        return (
            <Card>
                <Text variant="caption" weight="medium">
                    {strings.budget.overall}
                </Text>
                <Text tone="secondary">{strings.budget.noOverall}</Text>
            </Card>
        );
    }
    return (
        <>
            <Card testID="overall-card" style={styles.overall}>
                <Text variant="caption" weight="medium">
                    {strings.budget.overall}
                </Text>
                <View style={styles.amountRow}>
                    <MoneyText money={remaining} scope="HOUSEHOLD" scopeVisible={false} />
                    <Text tone="secondary" tabular style={styles.of}>
                        {strings.budget.of(formatMoney(limit))}
                    </Text>
                </View>
                <ProgressBar
                    percent={Number(c.percentage)}
                    percentText={c.percentage}
                    status={c.status}
                    valueText={strings.budget.progressA11y(c.percentage, formatMoney(remaining))}
                />
            </Card>
            <View style={styles.pair}>
                <Card accessible style={styles.stat}>
                    <Text variant="caption" tone="secondary">
                        {strings.budget.consumed}
                    </Text>
                    <Text variant="h2" tabular>
                        {formatMoney(consumed)}
                    </Text>
                </Card>
                <Card accessible style={styles.stat}>
                    <Text variant="caption" tone="secondary">
                        {strings.budget.remaining}
                    </Text>
                    <Text variant="h2" tabular>
                        {formatMoney(remaining)}
                    </Text>
                </Card>
            </View>
        </>
    );
}

/** BR-BUD-04: non-blocking, server-computed. */
function WarningBanner({ budget }: { budget: Budget }) {
    const colors = useColors();
    const w = budget.categoryLimitsWarning;
    const total = asMoney(w?.categoryLimitsTotal);
    const overall = asMoney(w?.overallLimit);
    if (!w || !total || !overall) return null;
    return (
        <View
            accessible
            accessibilityRole="alert"
            style={[styles.banner, { backgroundColor: colors.card }]}
        >
            <Icon name="warning" color={colors.warning} />
            <View style={styles.flex}>
                <Text weight="semibold">{strings.budget.warningTitle}</Text>
                <Text variant="caption" tone="secondary">
                    {strings.budget.warningMessage(formatMoney(total), formatMoney(overall))}
                </Text>
            </View>
        </View>
    );
}

/** Budget tab: view, set/replace and copy-previous for the current or a past household period. */
export function BudgetScreen() {
    const { household, readOnly, currentPeriod } = useHouseholdContext();
    const queryClient = useQueryClient();
    const router = useRouter();
    const toast = useToast();
    const colors = useColors();
    const [refreshing, setRefreshing] = useState(false);
    const [offset, setOffset] = useState(0);
    const [editing, setEditing] = useState(false);
    const [copyError, setCopyError] = useState<unknown>(null);
    // Per-navigation nonce (event handlers only): re-seeds the expense filters on a repeat drill-down.
    const drillDowns = useRef(0);
    const period = offset === 0 ? currentPeriod : shiftBudgetPeriod(currentPeriod, offset);
    const key = budgetKey(period.start);
    const past = period.start < currentPeriod.start;

    const budgetQuery = useQuery({
        queryKey: key,
        queryFn: () => fetchBudget(period.start),
        retry: false,
    });
    const categoriesQuery = useQuery({
        queryKey: CATEGORIES_KEY,
        queryFn: fetchAllCategories,
        retry: false,
    });
    const refresh = async () => {
        setRefreshing(true);
        try {
            await Promise.all([budgetQuery.refetch(), categoriesQuery.refetch()]);
        } finally {
            setRefreshing(false);
        }
    };
    const categories = categoriesQuery.data ?? [];
    const budget = budgetQuery.data ?? null;

    const save = useMutation({
        mutationFn: (form: BudgetForm) =>
            setBudget(period.start, toSetBudgetRequest(form, household.currency), budget?.version),
        onSuccess: () => {
            setEditing(false);
            toast(strings.budget.saved, "success");
            void queryClient.invalidateQueries({ queryKey: key });
        },
    });
    const copy = useMutation({
        mutationFn: () => copyPreviousBudget(period.start),
        onMutate: () => setCopyError(null),
        onSuccess: () => {
            toast(strings.budget.copied, "success");
            void queryClient.invalidateQueries({ queryKey: key });
        },
        onError: (e) => {
            setCopyError(e);
            void queryClient.invalidateQueries({ queryKey: key });
        },
    });

    const openEditor = () => {
        save.reset();
        setEditing(true);
    };
    const move = (delta: number) => {
        setOffset((o) => o + delta);
        setCopyError(null);
        setEditing(false);
    };
    // Same drill-down as the dashboard categories: the expenses of that category, household scope.
    const openCategory = (categoryId: string) =>
        router.push({
            pathname: "/expenses",
            params: {
                categoryId,
                periodStart: period.start,
                scope: "HOUSEHOLD",
                nav: `budget-${++drillDowns.current}`,
            },
        });
    const prevLabel = periodLabel(shiftBudgetPeriod(period, -1));
    const nextLabel = periodLabel(shiftBudgetPeriod(period, 1));

    let body;
    if (budgetQuery.isPending) {
        body = (
            <>
                <Skeleton variant="card" />
                <Skeleton variant="row" />
                <Skeleton variant="row" />
            </>
        );
    } else if (budgetQuery.isError) {
        body = (
            <ErrorState
                message={errorMessage(budgetQuery.error)}
                onRetry={() => void budgetQuery.refetch()}
            />
        );
    } else if (budget === null) {
        body = (
            <>
                <EmptyState
                    title={strings.budget.emptyTitle}
                    message={readOnly ? strings.budget.emptyReadOnly : strings.budget.emptyMessage}
                />
                {copyError ? (
                    <Text tone="danger" accessibilityLiveRegion="polite">
                        {errorMessage(copyError)}
                    </Text>
                ) : null}
                {readOnly ? null : (
                    <>
                        <Button size="lg" label={strings.budget.set} onPress={openEditor} />
                        <Button
                            size="lg"
                            label={strings.budget.copyPrevious}
                            variant="secondary"
                            loading={copy.isPending}
                            onPress={() => copy.mutate()}
                        />
                    </>
                )}
            </>
        );
    } else {
        body = (
            <>
                <WarningBanner budget={budget} />
                <OverallCard budget={budget} />
                <SectionTitle>{strings.budget.categoryProgressTitle}</SectionTitle>
                <Card style={styles.categories}>
                    {budget.categoryLimits.length === 0 ? (
                        <Text tone="secondary" style={styles.none}>
                            {strings.budget.noCategories}
                        </Text>
                    ) : (
                        budget.categoryLimits.map((l, index) => {
                            const category = categories.find((c) => c.id === l.categoryId);
                            return (
                                <Fragment key={l.categoryId}>
                                    {index > 0 ? <Divider /> : null}
                                    <CategoryLimitRow
                                        name={categoryName(category)}
                                        limit={l.limit}
                                        consumption={l.consumption}
                                        visual={categoryVisual(category)}
                                        onPress={
                                            l.consumption
                                                ? () => openCategory(l.categoryId)
                                                : undefined
                                        }
                                    />
                                </Fragment>
                            );
                        })
                    )}
                </Card>
                {readOnly ? null : (
                    <Button
                        size="lg"
                        label={strings.budget.edit}
                        variant="outline"
                        onPress={openEditor}
                    />
                )}
                <Text variant="caption" tone="secondary">
                    {strings.budget.scopeNote}
                </Text>
            </>
        );
    }

    return (
        <Screen
            refreshControl={
                <RefreshControl
                    refreshing={refreshing}
                    onRefresh={() => void refresh()}
                    tintColor={colors.primary}
                />
            }
        >
            <ScreenTitle>{strings.tabs.budget}</ScreenTitle>
            <View style={styles.header}>
                <IconButton
                    icon="chevronBack"
                    label={strings.budget.previousPeriod(prevLabel)}
                    onPress={() => move(-1)}
                />
                <View accessible accessibilityRole="header" style={styles.flex}>
                    <Text weight="semibold" style={styles.centered} testID="budget-period">
                        {periodTitle(period)}
                    </Text>
                </View>
                {/* The future is never browsable: the control keeps its place, disabled. */}
                <IconButton
                    icon="chevron"
                    label={strings.budget.nextPeriod(nextLabel)}
                    disabled={offset >= 0}
                    onPress={() => move(1)}
                />
            </View>
            {past ? (
                <Text variant="caption" tone="secondary">
                    {strings.budget.pastNote}
                </Text>
            ) : null}
            {body}
            <BudgetEditSheet
                visible={editing && !readOnly}
                budget={budget}
                categories={categories}
                currency={household.currency}
                past={past}
                saving={save.isPending}
                error={save.error}
                onSubmit={(form) => save.mutate(form)}
                onReload={() => {
                    setEditing(false);
                    void budgetQuery.refetch();
                }}
                onClose={() => setEditing(false)}
            />
        </Screen>
    );
}

const styles = StyleSheet.create({
    header: { flexDirection: "row", alignItems: "center", gap: spacing.md },
    flex: { flex: 1 },
    centered: { textAlign: "center" },
    overall: { gap: spacing.md },
    amountRow: { flexDirection: "row", alignItems: "flex-end", flexWrap: "wrap", gap: spacing.sm },
    of: { paddingBottom: spacing.xs },
    pair: { flexDirection: "row", gap: spacing.md },
    stat: { flex: 1, gap: spacing.xs },
    categories: { gap: spacing.none, paddingVertical: spacing.xs },
    none: { paddingVertical: spacing.md },
    banner: {
        flexDirection: "row",
        gap: spacing.md,
        padding: spacing.lg,
        borderRadius: radius.lg,
        alignItems: "flex-start",
    },
});
