import { Pressable, StyleSheet, View } from "react-native";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { type IconName } from "./theme/icons";
import { useColors } from "./theme/theme";
import { borderWidth, minTouchTarget, radius, size, spacing } from "./theme/tokens";

export function Chip({
    label,
    selected,
    onPress,
}: {
    label: string;
    selected: boolean;
    onPress: () => void;
}) {
    const colors = useColors();
    return (
        <Pressable
            accessibilityRole="radio"
            accessibilityLabel={label}
            accessibilityState={{ checked: selected, selected }}
            onPress={onPress}
            style={[
                styles.chip,
                {
                    backgroundColor: selected ? colors.primary : colors.card,
                    borderColor: selected ? colors.primary : colors.border,
                },
            ]}
        >
            <Text
                variant="caption"
                weight={selected ? "semibold" : "medium"}
                style={{ color: selected ? colors.onPrimary : colors.text }}
            >
                {label}
            </Text>
        </Pressable>
    );
}

/** Filter entry point of a list header: icon, label and either a count badge or a disclosure chevron. */
export function FilterChip({
    label,
    icon,
    count,
    removable,
    onPress,
    accessibilityLabel,
    testID,
}: {
    label: string;
    icon?: IconName;
    /** Number of active filters behind this chip; shown as a badge when above zero. */
    count?: number;
    /** Active filter chip: a close mark replaces the disclosure chevron. */
    removable?: boolean;
    onPress: () => void;
    accessibilityLabel?: string;
    testID?: string;
}) {
    const colors = useColors();
    return (
        <Pressable
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={accessibilityLabel ?? label}
            onPress={onPress}
            style={[
                styles.chip,
                styles.filter,
                { backgroundColor: colors.card, borderColor: colors.border },
            ]}
        >
            {icon ? <Icon name={icon} size="sm" /> : null}
            <Text variant="caption" weight="medium" numberOfLines={1}>
                {label}
            </Text>
            {count !== undefined && count > 0 ? (
                <View style={[styles.badge, { backgroundColor: colors.primary }]}>
                    <Text variant="label" weight="semibold" style={{ color: colors.onPrimary }}>
                        {count}
                    </Text>
                </View>
            ) : (
                <Icon name={removable ? "close" : "chevronDown"} size="sm" />
            )}
        </Pressable>
    );
}

export interface Option<T extends string> {
    value: T;
    label: string;
}

/** Single-choice selector built from chips. */
export function Selector<T extends string>({
    options,
    value,
    onChange,
    label,
}: {
    options: readonly Option<T>[];
    value: T | null;
    onChange: (value: T) => void;
    label: string;
}) {
    return (
        <View accessibilityRole="radiogroup" accessibilityLabel={label} style={styles.group}>
            {options.map((o) => (
                <Chip
                    key={o.value}
                    label={o.label}
                    selected={o.value === value}
                    onPress={() => onChange(o.value)}
                />
            ))}
        </View>
    );
}

/** Segmented control of the approved design: one pill track, the active segment filled in primary. */
export function Segmented<T extends string>({
    options,
    value,
    onChange,
    label,
    testID,
}: {
    options: readonly Option<T>[];
    value: T;
    onChange: (value: T) => void;
    label: string;
    testID?: string;
}) {
    const colors = useColors();
    return (
        <View
            testID={testID}
            accessibilityRole="radiogroup"
            accessibilityLabel={label}
            style={[styles.track, { backgroundColor: colors.card, borderColor: colors.border }]}
        >
            {options.map((o) => {
                const selected = o.value === value;
                return (
                    <Pressable
                        key={o.value}
                        accessibilityRole="radio"
                        accessibilityLabel={o.label}
                        accessibilityState={{ checked: selected, selected }}
                        onPress={() => onChange(o.value)}
                        style={[
                            styles.segment,
                            selected ? { backgroundColor: colors.primary } : null,
                        ]}
                    >
                        <Text
                            variant="caption"
                            weight={selected ? "semibold" : "medium"}
                            numberOfLines={1}
                            style={{ color: selected ? colors.onPrimary : colors.text }}
                        >
                            {o.label}
                        </Text>
                    </Pressable>
                );
            })}
        </View>
    );
}

const styles = StyleSheet.create({
    group: { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
    chip: {
        minHeight: Math.max(size.controlSm, minTouchTarget),
        minWidth: minTouchTarget,
        borderRadius: radius.pill,
        borderWidth: borderWidth.hairline,
        paddingHorizontal: spacing.lg,
        alignItems: "center",
        justifyContent: "center",
    },
    filter: { flexDirection: "row", gap: spacing.sm },
    badge: {
        minWidth: size.badge,
        height: size.badge,
        borderRadius: radius.pill,
        paddingHorizontal: spacing.xs,
        alignItems: "center",
        justifyContent: "center",
    },
    track: {
        flexDirection: "row",
        minHeight: Math.max(size.controlMd, minTouchTarget),
        borderRadius: radius.pill,
        borderWidth: borderWidth.hairline,
    },
    segment: {
        flex: 1,
        minHeight: Math.max(size.controlMd, minTouchTarget),
        borderRadius: radius.pill,
        paddingHorizontal: spacing.sm,
        alignItems: "center",
        justifyContent: "center",
    },
});
