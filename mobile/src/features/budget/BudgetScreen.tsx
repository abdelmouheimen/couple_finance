import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { RefreshControl, StyleSheet, View } from "react-native";
import { shiftBudgetPeriod } from "@/features/household/currentPeriod";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import type { components } from "@/shared/api/client";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { formatMoney, type Money } from "@/shared/money/money";
import {
    Button,
    Card,
    EmptyState,
    ErrorState,
    Icon,
    IconButton,
    MoneyText,
    ProgressBar,
    Screen,
    Skeleton,
    Text,
    useColors,
    useToast,
} from "@/shared/ui";
import { fabContentInset, minTouchTarget, radius, spacing } from "@/shared/ui/theme/tokens";
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
import { periodLabel } from "./periodLabel";

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
    return (
        <Card>
            <Text variant="title" accessibilityRole="header">
                {strings.budget.overall}
            </Text>
            {c && limit && remaining && consumed && c.status && c.percentage !== undefined ? (
                <>
                    <Text variant="caption" tone="secondary" bold>
                        {strings.budget.remaining}
                    </Text>
                    <MoneyText money={remaining} scope="HOUSEHOLD" variant="headline" />
                    <ProgressBar
                        percent={Number(c.percentage)}
                        percentText={c.percentage}
                        status={c.status}
                        valueText={strings.budget.progressA11y(
                            c.percentage,
                            formatMoney(remaining),
                        )}
                    />
                    <View style={styles.pair}>
                        <View accessible style={styles.flex}>
                            <Text variant="caption" tone="secondary">
                                {strings.budget.consumed}
                            </Text>
                            <Text bold tabular>
                                {formatMoney(consumed)}
                            </Text>
                        </View>
                        <View accessible style={styles.flex}>
                            <Text variant="caption" tone="secondary">
                                {strings.budget.limit}
                            </Text>
                            <Text bold tabular>
                                {formatMoney(limit)}
                            </Text>
                        </View>
                    </View>
                </>
            ) : (
                <Text tone="secondary">{strings.budget.noOverall}</Text>
            )}
            <Text variant="caption" tone="secondary">
                {strings.budget.scopeNote}
            </Text>
        </Card>
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
            style={[styles.banner, { backgroundColor: colors.surfaceMuted }]}
        >
            <Icon name="warning" color={colors.warning} />
            <View style={styles.flex}>
                <Text bold>{strings.budget.warningTitle}</Text>
                <Text>
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
    const toast = useToast();
    const colors = useColors();
    const [refreshing, setRefreshing] = useState(false);
    const [offset, setOffset] = useState(0);
    const [editing, setEditing] = useState(false);
    const [copyError, setCopyError] = useState<unknown>(null);
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
                        <Button label={strings.budget.set} onPress={openEditor} />
                        <Button
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
                <Card>
                    <Text variant="title" accessibilityRole="header">
                        {strings.budget.categories}
                    </Text>
                    {budget.categoryLimits.length === 0 ? (
                        <Text tone="secondary">{strings.budget.noCategories}</Text>
                    ) : (
                        budget.categoryLimits.map((l) => (
                            <CategoryLimitRow
                                key={l.categoryId}
                                name={categoryName(categories.find((c) => c.id === l.categoryId))}
                                limit={l.limit}
                            />
                        ))
                    )}
                </Card>
                {readOnly ? null : (
                    <Button label={strings.budget.edit} variant="secondary" onPress={openEditor} />
                )}
            </>
        );
    }

    return (
        <Screen
            bottomInset={fabContentInset}
            refreshControl={
                <RefreshControl
                    refreshing={refreshing}
                    onRefresh={() => void refresh()}
                    tintColor={colors.primary}
                />
            }
        >
            <Text variant="headline" accessibilityRole="header">
                {strings.tabs.budget}
            </Text>
            <View style={styles.header}>
                <IconButton
                    icon="chevronBack"
                    label={strings.budget.previousPeriod(prevLabel)}
                    onPress={() => move(-1)}
                />
                <View accessible accessibilityRole="header" style={styles.flex}>
                    <Text variant="title" style={styles.centered}>
                        {periodLabel(period)}
                    </Text>
                </View>
                {offset < 0 ? (
                    <IconButton
                        icon="chevron"
                        label={strings.budget.nextPeriod(nextLabel)}
                        onPress={() => move(1)}
                    />
                ) : (
                    <View style={styles.spacer} />
                )}
            </View>
            {past ? <Text tone="secondary">{strings.budget.pastNote}</Text> : null}
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
    spacer: { width: minTouchTarget },
    pair: { flexDirection: "row", gap: spacing.md },
    banner: {
        flexDirection: "row",
        gap: spacing.md,
        padding: spacing.md,
        borderRadius: radius.md,
        alignItems: "flex-start",
    },
});
