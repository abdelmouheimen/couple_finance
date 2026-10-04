import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney } from "@/shared/money/money";
import { Button, Card, MoneyText, ProgressBar, Text } from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import type { AnalyticsScope, PeriodAnalytics } from "./analyticsApi";
import { toMoney } from "./dashboardModel";

/** Block 1: total, scope label and comparison, all verbatim from the API (BR-SCP-03, BR-MON-08). */
export function SpendSummaryCard({ data }: { data: PeriodAnalytics }) {
    const previous = data.previousPeriod;
    const total = toMoney(data.total);
    const difference = toMoney(previous?.difference);
    return (
        <Card>
            <Text variant="title" accessibilityRole="header">
                {strings.dashboard.spentTitle}
            </Text>
            {total ? <MoneyText money={total} scope={data.scope} /> : null}
            {difference ? (
                <Text tone="secondary" testID="previous-change">
                    {strings.dashboard.previousChange(
                        formatMoney(difference),
                        previous?.changePercentage,
                    )}
                </Text>
            ) : null}
        </Card>
    );
}

/**
 * Block 2: budget remaining. Renders nothing in PERSONAL scope without a budget (BR-BUD-02); in
 * HOUSEHOLD scope a missing budget shows a call to action.
 */
export function BudgetRemainingCard({
    data,
    scope,
    onSetBudget,
}: {
    data: PeriodAnalytics;
    scope: AnalyticsScope;
    onSetBudget: () => void;
}) {
    const budget = data.budget;
    const remaining = toMoney(budget?.remaining);
    const limit = toMoney(budget?.limit);
    if (!budget || !remaining || !limit || !budget.status) {
        if (scope === "PERSONAL") return null;
        return (
            <Card>
                <Text variant="title" accessibilityRole="header">
                    {strings.dashboard.budgetTitle}
                </Text>
                <Text tone="secondary">{strings.dashboard.noBudgetMessage}</Text>
                <Button label={strings.dashboard.setBudget} onPress={onSetBudget} />
            </Card>
        );
    }
    return (
        <Card>
            <Text variant="title" accessibilityRole="header">
                {strings.dashboard.budgetTitle}
            </Text>
            <View style={styles.column}>
                <MoneyText money={remaining} scope={data.scope} />
                <Text tone="secondary">{strings.dashboard.budgetOf(formatMoney(limit))}</Text>
            </View>
            <ProgressBar percent={Number(budget.percentage ?? 0)} status={budget.status} />
        </Card>
    );
}

const styles = StyleSheet.create({
    column: { gap: spacing.xs },
});
