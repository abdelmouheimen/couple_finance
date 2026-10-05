import { useState } from "react";
import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney } from "@/shared/money/money";
import { Card, ListRow, Text, useChartColors, useColors } from "@/shared/ui";
import { borderWidth, radius, spacing, swatchSize } from "@/shared/ui/theme/tokens";
import { COLLAPSED_LIST_ROWS, donutSummary, MAX_DONUT_SLICES, toDonutSlices } from "./chartModel";
import { DonutChart } from "./charts/DonutChart";
import type { CategoryRow } from "./dashboardModel";

interface Props {
    /** Every category of the period as returned by the API (largest first). */
    rows: readonly CategoryRow[];
    refunds: readonly CategoryRow[];
    scopeLabel: string;
    onSelect: (categoryId: string) => void;
}

const MAX_REFUNDS = 3;

function Swatch({ color }: { color: string }) {
    return (
        <View
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
            style={[styles.swatch, { backgroundColor: color }]}
        />
    );
}

/**
 * "Where your money goes": a donut of the top categories (+ "Other" in the chart only) and the ranked
 * list. Both render the same server values (BR-ANA-05); the full list is one tap away so "Other" never
 * hides a category. Negative (refund) categories stay outside the donut (BR-ANA-06).
 */
export function CategoryBreakdown({ rows, refunds, scopeLabel, onSelect }: Props) {
    const colors = useColors();
    const chart = useChartColors();
    const [expanded, setExpanded] = useState(false);
    const slices = toDonutSlices(rows);
    const colorOf = (index: number) => chart.series[index] ?? chart.other;
    const grouped = rows.length > MAX_DONUT_SLICES;
    const visible = expanded || !grouped ? rows : rows.slice(0, COLLAPSED_LIST_ROWS);
    const otherSlice = slices.find((s) => s.other);

    return (
        <Card>
            <Text variant="title" accessibilityRole="header">
                {strings.dashboard.whereTitle}
            </Text>
            {slices.length > 0 ? (
                <View style={styles.donut}>
                    <DonutChart
                        testID="category-chart"
                        trackColor={colors.surfaceMuted}
                        accessibilityLabel={donutSummary(slices, scopeLabel)}
                        arcs={slices.map((s, i) => ({
                            key: s.key,
                            tenths: s.tenths,
                            color: s.other ? chart.other : colorOf(i),
                        }))}
                    />
                </View>
            ) : (
                <Text tone="secondary" testID="category-chart-empty">
                    {strings.dashboard.noChartData}
                </Text>
            )}
            <View>
                {visible.map((r, index) => (
                    <ListRow
                        key={r.categoryId}
                        testID={`category-row-${r.categoryId}`}
                        title={r.name}
                        leading={<Swatch color={colorOf(index)} />}
                        subtitle={
                            r.percentage !== undefined
                                ? `${formatMoney(r.total)} · ${r.percentage} %`
                                : formatMoney(r.total)
                        }
                        accessibilityHint={strings.dashboard.categoryHint}
                        onPress={() => onSelect(r.categoryId)}
                    />
                ))}
                {grouped && !expanded && otherSlice ? (
                    <ListRow
                        testID="category-other-row"
                        title={strings.dashboard.otherRow(rows.length - COLLAPSED_LIST_ROWS)}
                        leading={<Swatch color={chart.other} />}
                        subtitle={`${otherSlice.percentage} %`}
                        onPress={() => setExpanded(true)}
                    />
                ) : null}
                {grouped ? (
                    <ListRow
                        testID="category-toggle"
                        title={
                            expanded
                                ? strings.dashboard.showTopCategories
                                : strings.dashboard.showAllCategories(rows.length)
                        }
                        onPress={() => setExpanded((v) => !v)}
                    />
                ) : null}
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
    donut: { alignItems: "center", paddingVertical: spacing.xs },
    swatch: { width: swatchSize, height: swatchSize, borderRadius: radius.sm },
    refunds: {
        gap: spacing.xs,
        paddingTop: spacing.sm,
        borderTopWidth: borderWidth.hairline,
    },
});
