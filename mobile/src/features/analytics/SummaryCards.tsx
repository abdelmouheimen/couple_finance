import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney } from "@/shared/money/money";
import {
    Button,
    Card,
    MoneyText,
    ProgressRing,
    StatusPill,
    Text,
    useStatusColor,
} from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import type { AnalyticsScope, PeriodAnalytics } from "./analyticsApi";
import { toMoney } from "./dashboardModel";

type Budget = NonNullable<PeriodAnalytics["budget"]>;

function BudgetRing({ budget }: { budget: Budget }) {
    const tone = useStatusColor(budget.status!);
    const percentage = budget.percentage ?? "0";
    return (
        <ProgressRing
            testID="budget-ring"
            percent={Number(percentage)}
            label={strings.percent(percentage)}
            color={tone}
            accessibilityLabel={strings.dashboard.ringA11y(
                percentage,
                strings.budgetStatus[budget.status!],
            )}
        />
    );
}

/**
 * Top of the dashboard: the monthly budget card (remaining, limit, status, consumption ring) and the
 * "spent / remaining" pair. Every figure is verbatim from the API (BR-ANA-01, BR-MON-08) and carries its
 * scope for assistive tech; on screen the scope is the selected segment right above (BR-SCP-03). The
 * budget part renders nothing in PERSONAL scope (BR-BUD-02); in HOUSEHOLD scope a missing budget shows a
 * call to action.
 */
export function SpendBudgetCard({
    data,
    scope,
    previousLabel,
    onSetBudget,
}: {
    data: PeriodAnalytics;
    scope: AnalyticsScope;
    /** Short name of the previous period ("sept."), for the comparison line. */
    previousLabel: string;
    onSetBudget: () => void;
}) {
    const previous = data.previousPeriod;
    const total = toMoney(data.total);
    const difference = toMoney(previous?.difference);
    const budget = data.budget;
    const remaining = toMoney(budget?.remaining);
    const limit = toMoney(budget?.limit);
    const hasBudget = budget && remaining && limit && budget.status;
    const change =
        previous?.changePercentage !== undefined
            ? strings.dashboard.previousChange(previous.changePercentage, previousLabel)
            : difference
              ? strings.dashboard.previousDifference(formatMoney(difference), previousLabel)
              : undefined;
    return (
        <View style={styles.block}>
            {hasBudget ? (
                <Card testID="budget-card" style={styles.hero}>
                    <View style={styles.heroText}>
                        <Text variant="caption" weight="medium">
                            {strings.dashboard.budgetTitle}
                        </Text>
                        <MoneyText money={remaining} scope={data.scope} scopeVisible={false} />
                        <Text tone="secondary" tabular>
                            {strings.dashboard.budgetOf(formatMoney(limit))}
                        </Text>
                        <View style={styles.status}>
                            <StatusPill status={budget.status!} />
                        </View>
                    </View>
                    <BudgetRing budget={budget} />
                </Card>
            ) : scope === "HOUSEHOLD" ? (
                <Card testID="budget-card">
                    <Text variant="caption" weight="medium">
                        {strings.dashboard.budgetTitle}
                    </Text>
                    <Text tone="secondary">{strings.dashboard.noBudgetMessage}</Text>
                    <Button
                        label={strings.dashboard.setBudget}
                        variant="outline"
                        onPress={onSetBudget}
                    />
                </Card>
            ) : null}
            <View style={styles.pair}>
                {total ? (
                    <Card testID="spent-card" style={styles.stat}>
                        <Text variant="caption" tone="secondary">
                            {strings.dashboard.spent}
                        </Text>
                        <MoneyText
                            money={total}
                            scope={data.scope}
                            scopeVisible={false}
                            variant="h2"
                        />
                        {change ? (
                            <Text variant="caption" tone="secondary" testID="previous-change">
                                {change}
                            </Text>
                        ) : null}
                    </Card>
                ) : null}
                {hasBudget ? (
                    <Card testID="remaining-card" style={styles.stat}>
                        <Text variant="caption" tone="secondary">
                            {strings.dashboard.remaining}
                        </Text>
                        <MoneyText
                            money={remaining}
                            scope={data.scope}
                            scopeVisible={false}
                            variant="h2"
                        />
                        {budget.percentage !== undefined ? (
                            <Text variant="caption" tone="secondary">
                                {strings.dashboard.budgetUsed(budget.percentage)}
                            </Text>
                        ) : null}
                    </Card>
                ) : null}
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    block: { gap: spacing.md },
    hero: { flexDirection: "row", alignItems: "center", gap: spacing.lg },
    heroText: { flex: 1, gap: spacing.xs },
    status: { paddingTop: spacing.sm },
    pair: { flexDirection: "row", gap: spacing.md },
    stat: { flex: 1, gap: spacing.xs },
});
