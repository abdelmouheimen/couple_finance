import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney } from "@/shared/money/money";
import { Card, ListRow, Text, useColors } from "@/shared/ui";
import { borderWidth, progressBarHeight, radius, spacing } from "@/shared/ui/theme/tokens";
import type { CategoryRow } from "./dashboardModel";

interface Props {
    rows: readonly CategoryRow[];
    refunds: readonly CategoryRow[];
    onSelect: (categoryId: string) => void;
}

const MAX_REFUNDS = 3;

/** Bar length is the server percentage clamped for layout only; the figure shown is the server string. */
function barWidth(percentage: string | undefined): `${number}%` {
    const n = Number(percentage);
    return `${Number.isFinite(n) ? Math.max(0, Math.min(100, n)) : 0}%`;
}

/** Horizontal bars (core views) + the equivalent accessible list; both render the same API values. */
export function CategoryBreakdown({ rows, refunds, onSelect }: Props) {
    const colors = useColors();
    return (
        <Card>
            <Text variant="title" accessibilityRole="header">
                {strings.dashboard.whereTitle}
            </Text>
            <View testID="category-chart" style={styles.chart}>
                {rows.map((r) => (
                    <View key={r.categoryId}>
                        <ListRow
                            testID={`category-row-${r.categoryId}`}
                            title={r.name}
                            subtitle={
                                r.percentage !== undefined
                                    ? `${formatMoney(r.total)} · ${r.percentage} %`
                                    : formatMoney(r.total)
                            }
                            accessibilityHint={strings.dashboard.categoryHint}
                            onPress={() => onSelect(r.categoryId)}
                        />
                        <View
                            importantForAccessibility="no-hide-descendants"
                            accessibilityElementsHidden
                            style={[styles.track, { backgroundColor: colors.surfaceMuted }]}
                        >
                            <View
                                testID="category-bar"
                                style={[
                                    styles.fill,
                                    {
                                        width: barWidth(r.percentage),
                                        backgroundColor: colors.primary,
                                    },
                                ]}
                            />
                        </View>
                    </View>
                ))}
            </View>
            {refunds.length > 0 ? (
                <View style={[styles.refunds, { borderTopColor: colors.border }]}>
                    <Text variant="caption" tone="secondary" bold>
                        {strings.dashboard.refundsTitle}
                    </Text>
                    {refunds.slice(0, MAX_REFUNDS).map((r) => (
                        <ListRow
                            key={r.categoryId}
                            testID={`refund-row-${r.categoryId}`}
                            title={r.name}
                            subtitle={formatMoney(r.total)}
                            onPress={() => onSelect(r.categoryId)}
                        />
                    ))}
                </View>
            ) : null}
        </Card>
    );
}

const styles = StyleSheet.create({
    chart: { gap: spacing.sm },
    track: { height: progressBarHeight, borderRadius: radius.pill, overflow: "hidden" },
    fill: { height: progressBarHeight, borderRadius: radius.pill },
    refunds: {
        gap: spacing.xs,
        paddingTop: spacing.sm,
        borderTopWidth: borderWidth.hairline,
    },
});
