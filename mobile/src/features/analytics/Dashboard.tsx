import { useQuery } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { useState } from "react";
import { RefreshControl } from "react-native";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { EmptyState, ErrorState, Screen, Selector, Skeleton, Text, useColors } from "@/shared/ui";
import { fabContentInset } from "@/shared/ui/theme/tokens";
import {
    type AnalyticsScope,
    CATEGORIES_QUERY_KEY,
    fetchAllCategories,
    fetchPeriodAnalytics,
    periodAnalyticsKey,
} from "./analyticsApi";
import { CategoryBreakdown } from "./CategoryBreakdown";
import { toCategoryRows, toNegativeRows } from "./dashboardModel";
import { BudgetRemainingCard, SpendSummaryCard } from "./SummaryCards";

/** Display only (no period math): the ISO business date in the device locale, timezone-neutral. */
function displayDate(iso: string): string {
    const [y, m, d] = iso.split("-").map(Number);
    if (!y || !m || !d) return iso;
    return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString(undefined, {
        dateStyle: "medium",
        timeZone: "UTC",
    });
}

const SCOPES: readonly { value: AnalyticsScope; label: string }[] = [
    { value: "HOUSEHOLD", label: strings.scope.HOUSEHOLD },
    { value: "PERSONAL", label: strings.scope.PERSONAL },
];

/** Home tab: spent / budget remaining / where it goes, for one scope at a time. */
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
                    <SpendSummaryCard data={data} />
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
                <SpendSummaryCard data={data} />
                <BudgetRemainingCard data={data} scope={scope} onSetBudget={openBudget} />
                {categories.isError ? (
                    <ErrorState
                        message={errorMessage(categories.error)}
                        onRetry={() => void categories.refetch()}
                    />
                ) : categories.data ? (
                    <CategoryBreakdown
                        rows={toCategoryRows(data.categories, categories.data)}
                        refunds={toNegativeRows(data.negativeCategories, categories.data)}
                        onSelect={openCategory}
                    />
                ) : (
                    <Skeleton variant="card" />
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
                {strings.tabs.home}
            </Text>
            <Text variant="caption" tone="secondary" testID="period-header">
                {strings.dashboard.periodHeader(
                    displayDate(currentPeriod.start),
                    displayDate(currentPeriod.end),
                )}
            </Text>
            <Selector
                label={strings.dashboard.scopeLabel}
                options={SCOPES}
                value={scope}
                onChange={setScope}
            />
            {body()}
        </Screen>
    );
}
