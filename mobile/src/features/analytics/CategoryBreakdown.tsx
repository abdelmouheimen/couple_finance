import { useState } from "react";
import { Pressable, StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney, type Money } from "@/shared/money/money";
import { Card, Dot, SectionTitle, Text, useChartColors, useColors } from "@/shared/ui";
import { minTouchTarget, spacing } from "@/shared/ui/theme/tokens";
import { COLLAPSED_LIST_ROWS, donutSummary, MAX_DONUT_SLICES, toDonutSlices } from "./chartModel";
import { DonutChart } from "./charts/DonutChart";
import type { CategoryRow } from "./dashboardModel";

interface Props {
    /** Every category of the period as returned by the API (largest first). */
    rows: readonly CategoryRow[];
    refunds: readonly CategoryRow[];
    /** Net total of the period, verbatim from the API; shown in the centre of the donut. */
    total?: Money | undefined;
    scopeLabel: string;
    onSelect: (categoryId: string) => void;
}

const MAX_REFUNDS = 3;

/** One legend entry: marker, name, then the server amount and percentage. */
function LegendRow({
    testID,
    color,
    name,
    amount,
    percentage,
    hint,
    onPress,
}: {
    testID: string;
    color?: string;
    name: string;
    amount?: string;
    percentage?: string | undefined;
    hint?: string;
    onPress: () => void;
}) {
    const detail = [amount, percentage !== undefined ? strings.percent(percentage) : undefined]
        .filter((part) => part !== undefined)
        .join(", ");
    return (
        <Pressable
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={detail ? `${name}, ${detail}` : name}
            accessibilityHint={hint}
            onPress={onPress}
            style={styles.legendRow}
        >
            {color ? <Dot color={color} /> : null}
            <View style={styles.legendText}>
                <Text variant="caption" weight="medium" numberOfLines={1}>
                    {name}
                </Text>
                {amount ? (
                    <Text variant="caption" tone="secondary" tabular>
                        {amount}
                    </Text>
                ) : null}
            </View>
            {percentage !== undefined ? (
                <Text variant="caption" weight="medium" tabular>
                    {strings.percent(percentage)}
                </Text>
            ) : null}
        </Pressable>
    );
}

/**
 * "Répartition par catégorie": a donut of the top categories (+ "Autres" in the chart only) with the
 * period total in its centre, next to the ranked legend. Both render the same server values (BR-ANA-05);
 * the full list is one tap away so "Autres" never hides a category. Negative (refund) categories stay
 * outside the donut (BR-ANA-06).
 */
export function CategoryBreakdown({ rows, refunds, total, scopeLabel, onSelect }: Props) {
    const colors = useColors();
    const chart = useChartColors();
    const [expanded, setExpanded] = useState(false);
    const slices = toDonutSlices(rows);
    const colorOf = (index: number) => chart.series[index] ?? chart.other;
    const grouped = rows.length > MAX_DONUT_SLICES;
    const visible = expanded || !grouped ? rows : rows.slice(0, COLLAPSED_LIST_ROWS);
    const otherSlice = slices.find((s) => s.other);

    return (
        <View style={styles.block}>
            <SectionTitle>{strings.dashboard.whereTitle}</SectionTitle>
            <View style={styles.body}>
                {slices.length > 0 ? (
                    <DonutChart
                        testID="category-chart"
                        trackColor={colors.surface}
                        accessibilityLabel={donutSummary(slices, scopeLabel)}
                        arcs={slices.map((s, i) => ({
                            key: s.key,
                            tenths: s.tenths,
                            color: s.other ? chart.other : colorOf(i),
                        }))}
                    >
                        {total ? (
                            <>
                                <Text weight="semibold" tabular numberOfLines={1}>
                                    {formatMoney(total)}
                                </Text>
                                <Text variant="caption" tone="secondary">
                                    {strings.dashboard.total}
                                </Text>
                            </>
                        ) : null}
                    </DonutChart>
                ) : null}
                <Card style={styles.legend}>
                    {slices.length === 0 ? (
                        <Text variant="caption" tone="secondary" testID="category-chart-empty">
                            {strings.dashboard.noChartData}
                        </Text>
                    ) : null}
                    {visible.map((r, index) => (
                        <LegendRow
                            key={r.categoryId}
                            testID={`category-row-${r.categoryId}`}
                            color={colorOf(index)}
                            name={r.name}
                            amount={formatMoney(r.total)}
                            percentage={r.percentage}
                            hint={strings.dashboard.categoryHint}
                            onPress={() => onSelect(r.categoryId)}
                        />
                    ))}
                    {grouped && !expanded && otherSlice ? (
                        <LegendRow
                            testID="category-other-row"
                            color={chart.other}
                            name={strings.dashboard.otherRow(rows.length - COLLAPSED_LIST_ROWS)}
                            percentage={otherSlice.percentage}
                            onPress={() => setExpanded(true)}
                        />
                    ) : null}
                    {grouped ? (
                        <Pressable
                            testID="category-toggle"
                            accessibilityRole="button"
                            onPress={() => setExpanded((v) => !v)}
                            style={styles.legendRow}
                        >
                            <Text variant="caption" tone="primary" weight="semibold">
                                {expanded
                                    ? strings.dashboard.showTopCategories
                                    : strings.dashboard.showAllCategories(rows.length)}
                            </Text>
                        </Pressable>
                    ) : null}
                </Card>
            </View>
            {refunds.length > 0 ? (
                <Card>
                    <Text variant="caption" tone="secondary">
                        {strings.dashboard.refundsTitle}
                    </Text>
                    {refunds.slice(0, MAX_REFUNDS).map((r) => (
                        <LegendRow
                            key={r.categoryId}
                            testID={`refund-row-${r.categoryId}`}
                            name={r.name}
                            amount={formatMoney(r.total)}
                            onPress={() => onSelect(r.categoryId)}
                        />
                    ))}
                </Card>
            ) : null}
        </View>
    );
}

const styles = StyleSheet.create({
    block: { gap: spacing.md },
    body: { flexDirection: "row", alignItems: "center", flexWrap: "wrap", gap: spacing.md },
    legend: { flex: 1, minWidth: spacing.xxl * 5, gap: spacing.none, paddingVertical: spacing.sm },
    legendRow: {
        minHeight: minTouchTarget,
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.sm,
    },
    legendText: { flex: 1 },
});
