import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney } from "@/shared/money/money";
import { Button, Card, MoneyText, ProgressBar, Text } from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import type { AnalyticsScope, PeriodAnalytics } from "./analyticsApi";
import { toMoney } from "./dashboardModel";

/**
 * Block 3 of the dashboard: spending of the period and budget status in ONE compact card. Every figure is
 * verbatim from the API (BR-ANA-01, BR-MON-08) and the scope is always labelled (BR-SCP-03). The budget part
 * renders nothing in PERSONAL scope without a budget (BR-BUD-02); in HOUSEHOLD scope a missing budget shows
 * a call to action.
 */
export function SpendBudgetCard({
    data,
    scope,
    onSetBudget,
}: {
    data: PeriodAnalytics;
    scope: AnalyticsScope;
    onSetBudget: () => void;
}) {
    const previous = data.previousPeriod;
    const total = toMoney(data.total);
    const difference = toMoney(previous?.difference);
    const budget = data.budget;
    const remaining = toMoney(budget?.remaining);
    const limit = toMoney(budget?.limit);
    const hasBudget = budget && remaining && limit && budget.status;
    return (
        <Card>
            <Text variant="title" accessibilityRole="header">
                {strings.dashboard.spentTitle}
            </Text>
            {total ? <MoneyText money={total} scope={data.scope} variant="headline" /> : null}
            {difference ? (
                <Text tone="secondary" testID="previous-change">
                    {strings.dashboard.previousChange(
                        formatMoney(difference),
                        previous?.changePercentage,
                    )}
                </Text>
            ) : null}
            {hasBudget ? (
                <View style={styles.budget}>
                    <Text variant="caption" tone="secondary" bold>
                        {strings.dashboard.budgetTitle}
                    </Text>
                    <MoneyText money={remaining} scope={data.scope} variant="title" />
                    <Text tone="secondary">{strings.dashboard.budgetOf(formatMoney(limit))}</Text>
                    <ProgressBar
                        percent={Number(budget.percentage ?? 0)}
                        percentText={budget.percentage}
                        status={budget.status!}
                    />
                </View>
            ) : scope === "HOUSEHOLD" ? (
                <View style={styles.budget}>
                    <Text tone="secondary">{strings.dashboard.noBudgetMessage}</Text>
                    <Button
                        label={strings.dashboard.setBudget}
                        variant="secondary"
                        onPress={onSetBudget}
                    />
                </View>
            ) : null}
        </Card>
    );
}

const styles = StyleSheet.create({
    budget: { gap: spacing.xs, paddingTop: spacing.xs },
});
