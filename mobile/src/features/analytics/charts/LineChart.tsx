import { useState } from "react";
import { type LayoutChangeEvent, StyleSheet, View } from "react-native";
import Svg, { Circle, Line, Polyline } from "react-native-svg";
import { formatMoney } from "@/shared/money/money";
import { Text, useColors } from "@/shared/ui";
import { chartHeight, chartPointRadius, chartStrokeWidth, spacing } from "@/shared/ui/theme/tokens";
import type { SeriesModel } from "../chartModel";

interface Props {
    model: SeriesModel;
    lineColor: string;
    accessibilityLabel: string;
    firstLabel: string;
    lastLabel: string;
    testID?: string;
}

/** Width used until the container reports its size (first frame / tests). */
const FALLBACK_WIDTH = 240;
const BUDGET_DASH = "6 4";

/**
 * Cumulative spending line with a zero (or negative-aware) baseline and an optional dashed budget line.
 * Axis labels are real Text (design-system fonts, scaling); the whole chart exposes one text alternative.
 */
export function LineChart({
    model,
    lineColor,
    accessibilityLabel,
    firstLabel,
    lastLabel,
    testID,
}: Props) {
    const colors = useColors();
    const [width, setWidth] = useState(FALLBACK_WIDTH);
    const onLayout = (e: LayoutChangeEvent) => {
        const next = Math.floor(e.nativeEvent.layout.width);
        if (next > 0 && next !== width) setWidth(next);
    };
    const pad = chartPointRadius + chartStrokeWidth;
    const x = (permille: number) => pad + ((width - 2 * pad) * permille) / 1000;
    const y = (permille: number) => chartHeight - pad - ((chartHeight - 2 * pad) * permille) / 1000;
    const polyline = model.points.map((p) => `${x(p.xPermille)},${y(p.yPermille)}`).join(" ");
    const last = model.last!;

    return (
        <View
            testID={testID}
            accessible
            accessibilityRole="image"
            accessibilityLabel={accessibilityLabel}
            style={styles.wrap}
        >
            <View style={styles.axis}>
                <Text variant="caption" tone="secondary" tabular numberOfLines={1}>
                    {formatMoney(model.top)}
                </Text>
                <Text variant="caption" tone="secondary" tabular numberOfLines={1}>
                    {formatMoney(model.bottom)}
                </Text>
            </View>
            <View style={styles.plot}>
                <View onLayout={onLayout}>
                    <Svg width={width} height={chartHeight}>
                        <Line
                            testID="chart-baseline"
                            x1={pad}
                            x2={width - pad}
                            y1={y(model.zeroYPermille)}
                            y2={y(model.zeroYPermille)}
                            stroke={colors.border}
                            strokeWidth={chartStrokeWidth / 2}
                        />
                        {model.limit ? (
                            <Line
                                testID="chart-budget-line"
                                x1={pad}
                                x2={width - pad}
                                y1={y(model.limit.yPermille)}
                                y2={y(model.limit.yPermille)}
                                stroke={colors.warning}
                                strokeWidth={chartStrokeWidth}
                                strokeDasharray={BUDGET_DASH}
                            />
                        ) : null}
                        {model.points.length > 1 ? (
                            <Polyline
                                testID="chart-line"
                                points={polyline}
                                fill="none"
                                stroke={lineColor}
                                strokeWidth={chartStrokeWidth}
                                strokeLinejoin="round"
                            />
                        ) : null}
                        <Circle
                            testID="chart-last-point"
                            cx={x(last.xPermille)}
                            cy={y(last.yPermille)}
                            r={chartPointRadius}
                            fill={lineColor}
                        />
                    </Svg>
                </View>
                <View style={styles.dates}>
                    <Text variant="caption" tone="secondary">
                        {firstLabel}
                    </Text>
                    <Text variant="caption" tone="secondary">
                        {lastLabel}
                    </Text>
                </View>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    wrap: { flexDirection: "row", gap: spacing.sm },
    axis: { justifyContent: "space-between", maxWidth: "35%", height: chartHeight },
    plot: { flex: 1 },
    dates: { flexDirection: "row", justifyContent: "space-between" },
});
