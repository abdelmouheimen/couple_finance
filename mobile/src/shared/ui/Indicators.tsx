import { StyleSheet, View } from "react-native";
import Svg, { Circle, G } from "react-native-svg";
import { strings } from "@/shared/i18n/strings";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { type IconName } from "./theme/icons";
import { useColors } from "./theme/theme";
import { radius, size, spacing } from "./theme/tokens";

export type BudgetStatus = "ON_TRACK" | "WARNING" | "EXCEEDED";

export function useStatusColor(status: BudgetStatus): string {
    const colors = useColors();
    return { ON_TRACK: colors.success, WARNING: colors.warning, EXCEEDED: colors.danger }[status];
}

/** Small filled dot (status and legend marker). Decorative: always next to a text label. */
export function Dot({ color }: { color: string }) {
    return (
        <View
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
            style={[styles.dot, { backgroundColor: color }]}
        />
    );
}

/** Budget status as a dot AND a written label (never colour alone). */
export function StatusPill({ status }: { status: BudgetStatus }) {
    const tone = useStatusColor(status);
    return (
        <View style={styles.pill}>
            <Dot color={tone} />
            <Text variant="caption" weight="semibold" style={{ color: tone }}>
                {strings.budgetStatus[status]}
            </Text>
        </View>
    );
}

/** Rounded square carrying a category (or merchant) icon. */
export function IconTile({ icon, color }: { icon: IconName; color: string }) {
    const colors = useColors();
    return (
        <View style={[styles.tile, { backgroundColor: color }]}>
            <Icon name={icon} color={colors.text} />
        </View>
    );
}

/** Household avatar placeholder: the API exposes no picture, so the design photo is not reproduced. */
export function Avatar({ large }: { large?: boolean }) {
    const colors = useColors();
    const side = large ? size.avatarLg : size.avatar;
    return (
        <View
            importantForAccessibility="no-hide-descendants"
            accessibilityElementsHidden
            style={[styles.avatar, { width: side, height: side, backgroundColor: colors.surface }]}
        >
            <Icon name="users" size={large ? "lg" : "md"} />
        </View>
    );
}

/**
 * Circular gauge with a centred label. `percent` only drives the arc geometry (clamped to 0-100);
 * the label is the server-provided text, shown verbatim.
 */
export function ProgressRing({
    percent,
    label,
    color,
    accessibilityLabel,
    testID,
}: {
    percent: number;
    label: string;
    color: string;
    accessibilityLabel: string;
    testID?: string;
}) {
    const colors = useColors();
    const center = size.ring / 2;
    const r = (size.ring - size.ringStroke) / 2;
    const circumference = 2 * Math.PI * r;
    const fill = Math.max(0, Math.min(100, percent));
    const length = (circumference * fill) / 100;
    return (
        <View
            testID={testID}
            accessible
            accessibilityRole="image"
            accessibilityLabel={accessibilityLabel}
            style={styles.ring}
        >
            <Svg width={size.ring} height={size.ring} viewBox={`0 0 ${size.ring} ${size.ring}`}>
                <G transform={`rotate(-90 ${center} ${center})`}>
                    <Circle
                        cx={center}
                        cy={center}
                        r={r}
                        stroke={colors.surface}
                        strokeWidth={size.ringStroke}
                        fill="none"
                    />
                    {fill > 0 ? (
                        <Circle
                            cx={center}
                            cy={center}
                            r={r}
                            stroke={color}
                            strokeWidth={size.ringStroke}
                            strokeLinecap="round"
                            fill="none"
                            strokeDasharray={`${length} ${circumference - length}`}
                        />
                    ) : null}
                </G>
            </Svg>
            <View style={styles.ringLabel}>
                <Text weight="semibold" tabular>
                    {label}
                </Text>
            </View>
        </View>
    );
}

const styles = StyleSheet.create({
    dot: { width: size.statusDot, height: size.statusDot, borderRadius: radius.pill },
    pill: { flexDirection: "row", alignItems: "center", gap: spacing.sm },
    tile: {
        width: size.iconTile,
        height: size.iconTile,
        borderRadius: radius.md,
        alignItems: "center",
        justifyContent: "center",
    },
    avatar: { borderRadius: radius.pill, alignItems: "center", justifyContent: "center" },
    ring: { width: size.ring, height: size.ring },
    ringLabel: {
        position: "absolute",
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        alignItems: "center",
        justifyContent: "center",
    },
});
