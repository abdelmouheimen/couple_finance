import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { type BudgetStatus, StatusPill, useStatusColor } from "./Indicators";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { radius, size, spacing } from "./theme/tokens";

export type { BudgetStatus } from "./Indicators";

/**
 * Budget consumption bar. `percent` is the backend-computed percentage (display only; never derived
 * on device). Status is conveyed by colour AND text: the caption row writes it, and callers of the
 * `compact` form render the status themselves when it is not "on track".
 */
export function ProgressBar({
    percent,
    status,
    percentText,
    valueText,
    compact,
}: {
    percent: number;
    status: BudgetStatus;
    /** Backend percentage string shown verbatim (no rounding on device); defaults to the rounded `percent`. */
    percentText?: string;
    /** Spoken value, e.g. "82.5 percent of budget used, 450 euros remaining". */
    valueText?: string;
    /** Bar only, without the "x% used / status" caption row. */
    compact?: boolean;
}) {
    const colors = useColors();
    const tone = useStatusColor(status);
    const fill = Math.max(0, Math.min(100, percent));
    const statusLabel = strings.budgetStatus[status];
    const shownPercent = strings.percent(percentText ?? String(Math.round(percent)));
    return (
        <View
            accessible
            accessibilityRole="progressbar"
            accessibilityLabel={`${statusLabel}, ${shownPercent}`}
            accessibilityValue={{
                min: 0,
                max: 100,
                now: Math.round(fill),
                ...(valueText ? { text: valueText } : {}),
            }}
            style={styles.wrap}
        >
            <View style={[styles.track, { backgroundColor: colors.surface }]}>
                <View
                    testID="progress-fill"
                    style={[styles.fill, { width: `${fill}%`, backgroundColor: tone }]}
                />
            </View>
            {compact ? null : (
                <View style={styles.caption}>
                    <Text variant="caption" tone="secondary">
                        <Text variant="caption" weight="semibold" tabular style={{ color: tone }}>
                            {shownPercent}
                        </Text>
                        {` ${strings.budget.usedSuffix}`}
                    </Text>
                    <StatusPill status={status} />
                </View>
            )}
        </View>
    );
}

const styles = StyleSheet.create({
    wrap: { gap: spacing.sm },
    track: { height: size.progressBar, borderRadius: radius.pill, overflow: "hidden" },
    fill: { height: size.progressBar, borderRadius: radius.pill },
    caption: {
        flexDirection: "row",
        alignItems: "center",
        justifyContent: "space-between",
        flexWrap: "wrap",
        gap: spacing.sm,
    },
});
