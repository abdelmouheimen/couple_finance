import { Pressable, StyleSheet, View } from "react-native";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { borderWidth, minTouchTarget, radius, spacing } from "./theme/tokens";

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
                    backgroundColor: selected ? colors.primary : colors.surface,
                    borderColor: selected ? colors.primary : colors.border,
                },
            ]}
        >
            <Text bold style={{ color: selected ? colors.onPrimary : colors.text }}>
                {label}
            </Text>
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

const styles = StyleSheet.create({
    group: { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
    chip: {
        minHeight: minTouchTarget,
        minWidth: minTouchTarget,
        borderRadius: radius.pill,
        borderWidth: borderWidth.hairline,
        paddingHorizontal: spacing.lg,
        alignItems: "center",
        justifyContent: "center",
    },
});
