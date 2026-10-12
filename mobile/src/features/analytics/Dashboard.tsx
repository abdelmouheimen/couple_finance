import { useQuery } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { useState } from "react";
import { Pressable, RefreshControl, StyleSheet, View } from "react-native";
import { shiftDate } from "@/features/expense/expenseRules";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { shiftBudgetPeriod } from "@/features/household/currentPeriod";
import { periodShortName, periodTitle } from "@/features/budget/periodLabel";
import {
    Avatar,
    EmptyState,
    ErrorState,
    Icon,
    Screen,
    Segmented,
    Skeleton,
    Text,
    useColors,
} from "@/shared/ui";
import { minTouchTarget, spacing } from "@/shared/ui/theme/tokens";
import {
    type AnalyticsScope,
    CATEGORIES_QUERY_KEY,
    fetchAllCategories,
    fetchPeriodAnalytics,
    periodAnalyticsKey,
} from "./analyticsApi";
import { CategoryBreakdown } from "./CategoryBreakdown";
import { toCategoryRows, toMoney, toNegativeRows } from "./dashboardModel";
import { RecentExpenses } from "./RecentExpenses";
import { SpendingOverTime } from "./SpendingOverTime";
import { SpendBudgetCard } from "./SummaryCards";

const SCOPES: readonly { value: AnalyticsScope; label: string }[] = [
    { value: "HOUSEHOLD", label: strings.scope.HOUSEHOLD },
    { value: "PERSONAL", label: strings.scope.PERSONAL },
];

/**
 * Home tab, for one scope at a time (BR-SCP-03). Order of the approved design: brand header, period,
 * scope, budget + spending, category breakdown, recent expenses; the spending-over-time chart (not in
 * the reference) follows them. Every figure comes from the backend.
 */
export function Dashboard() {
    const { currentPeriod } = useHouseholdContext();
    const router = useRouter();
    const colors = useColors();
    const [scope, setScope] = useState<AnalyticsScope>("HOUSEHOLD");
    const [refreshing, setRefreshing] = useState(false);

    const analytics = useQuery({
        queryKey: periodAnalyticsKey(currentPeriod.start, scope),
        queryFn: () => fetchPeriodAnalytics(currentPeriod.start, scope),
        retry: false,
    });
    const categories = useQuery({
        queryKey: CATEGORIES_QUERY_KEY,
        queryFn: fetchAllCategories,
        retry: false,
    });

    async function refresh() {
        setRefreshing(true);
        try {
            await Promise.all([analytics.refetch(), categories.refetch()]);
        } finally {
            setRefreshing(false);
        }
    }

    const openCategory = (categoryId: string) =>
        router.push({
            pathname: "/expenses",
            params: {
                categoryId,
                periodStart: currentPeriod.start,
                scope,
                nav: String(Date.now()), // per-navigation nonce: re-seeds the filters on a repeat drill-down
            },
        });
    const openBudget = () => router.push("/budget");
    const openAdd = () => router.push("/add-expense");

    const data = analytics.data;
    const previousLabel = periodShortName(shiftBudgetPeriod(currentPeriod, -1));

    function body() {
        if (!data) {
            if (analytics.isError) {
                return (
                    <ErrorState
                        message={errorMessage(analytics.error)}
                        onRetry={() => void analytics.refetch()}
                    />
                );
            }
            return (
                <>
                    <Skeleton variant="card" />
                    <Skeleton variant="card" />
                    <Skeleton variant="card" />
                </>
            );
        }
        const isEmpty = data.categories.length === 0 && data.negativeCategories.length === 0;
        if (isEmpty) {
            return (
                <>
                    <SpendBudgetCard
                        data={data}
                        scope={scope}
                        previousLabel={previousLabel}
                        onSetBudget={openBudget}
                    />
                    <EmptyState
                        title={strings.dashboard.emptyTitle}
                        message={strings.dashboard.emptyMessage}
                        actionLabel={strings.dashboard.emptyAction}
                        onAction={openAdd}
                    />
                </>
            );
        }
        return (
            <>
                <SpendBudgetCard
                    data={data}
                    scope={scope}
                    previousLabel={previousLabel}
                    onSetBudget={openBudget}
                />
                {categories.isError ? (
                    <ErrorState
                        message={errorMessage(categories.error)}
                        onRetry={() => void categories.refetch()}
                    />
                ) : categories.data ? (
                    <CategoryBreakdown
                        rows={toCategoryRows(data.categories, categories.data)}
                        refunds={toNegativeRows(data.negativeCategories, categories.data)}
                        total={toMoney(data.total)}
                        scopeLabel={strings.scope[scope]}
                        onSelect={openCategory}
                    />
                ) : (
                    <Skeleton variant="card" />
                )}
                <RecentExpenses
                    scope={scope}
                    periodStart={currentPeriod.start}
                    periodEnd={shiftDate(currentPeriod.end, -1, 0)}
                    categories={categories.data ?? []}
                />
                <SpendingOverTime periodStart={currentPeriod.start} scope={scope} />
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
            <View style={styles.header}>
                <Text variant="h2" weight="bold" accessibilityRole="header" style={styles.flex}>
                    {strings.appName}
                </Text>
                <Avatar />
            </View>
            {/* "Octobre 2026 >": period navigation lives on the Budget tab (design/DESIGN-SPEC.md A-03). */}
            <Pressable
                accessibilityRole="button"
                accessibilityHint={strings.dashboard.periodHint}
                onPress={openBudget}
                style={styles.period}
            >
                <Text weight="semibold" testID="period-header">
                    {periodTitle(currentPeriod)}
                </Text>
                <Icon name="chevron" size="sm" />
            </Pressable>
            <Segmented
                label={strings.dashboard.scopeLabel}
                options={SCOPES}
                value={scope}
                onChange={setScope}
            />
            {body()}
        </Screen>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    header: { flexDirection: "row", alignItems: "center", gap: spacing.md },
    period: {
        flexDirection: "row",
        alignItems: "center",
        alignSelf: "flex-start",
        gap: spacing.xs,
        minHeight: minTouchTarget,
    },
});
