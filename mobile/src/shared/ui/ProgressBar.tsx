import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { type IconName, progressBarHeight, radius, spacing } from "./theme/tokens";

export type BudgetStatus = "ON_TRACK" | "WARNING" | "EXCEEDED";

const STATUS_ICON: Record<BudgetStatus, IconName> = {
    ON_TRACK: "check",
    WARNING: "warning",
    EXCEEDED: "error",
};

/**
 * Budget consumption bar. `percent` is the backend-computed percentage (display only; never derived
 * on device). Status is conveyed by color AND icon AND text.
 */
export function ProgressBar({
    percent,
    status,
    percentText,
    valueText,
}: {
    percent: number;
    status: BudgetStatus;
    /** Backend percentage string shown verbatim (no rounding on device); defaults to the rounded `percent`. */
    percentText?: string;
    /** Spoken value, e.g. "82.5 percent of budget used, 450 euros remaining". */
    valueText?: string;
}) {
    const colors = useColors();
    const tone = { ON_TRACK: colors.success, WARNING: colors.warning, EXCEEDED: colors.danger }[
        status
    ];
    const fill = Math.max(0, Math.min(100, percent));
    const statusLabel = strings.budgetStatus[status];
    const shownPercent = `${percentText ?? Math.round(percent)} %`;
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
            <View style={[styles.track, { backgroundColor: colors.surfaceMuted }]}>
                <View
                    testID="progress-fill"
                    style={[styles.fill, { width: `${fill}%`, backgroundColor: tone }]}
                />
            </View>
            <View style={styles.caption}>
                <Icon name={STATUS_ICON[status]} size="md" color={tone} />
                <Text variant="caption" bold style={{ color: tone }}>
                    {statusLabel}
                </Text>
                <Text variant="caption" tone="secondary" tabular>
                    {shownPercent}
                </Text>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    wrap: { gap: spacing.xs },
    track: { height: progressBarHeight, borderRadius: radius.pill, overflow: "hidden" },
    fill: { height: progressBarHeight, borderRadius: radius.pill },
    caption: { flexDirection: "row", alignItems: "center", gap: spacing.sm },
});
