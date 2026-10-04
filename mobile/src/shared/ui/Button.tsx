import { ActivityIndicator, Pressable, StyleSheet } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { borderWidth, minTouchTarget, opacity, radius, spacing } from "./theme/tokens";

export type ButtonVariant = "primary" | "secondary" | "destructive";

interface Props {
    label: string;
    onPress: () => void;
    variant?: ButtonVariant;
    loading?: boolean;
    disabled?: boolean;
    testID?: string;
}

export function Button({ label, onPress, variant = "primary", loading, disabled, testID }: Props) {
    const colors = useColors();
    const palette = {
        primary: { bg: colors.primary, fg: colors.onPrimary },
        secondary: { bg: colors.secondary, fg: colors.onSecondary },
        destructive: { bg: colors.danger, fg: colors.onDanger },
    }[variant];
    const inactive = disabled || loading;
    return (
        <Pressable
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={label}
            accessibilityState={{ disabled: !!inactive, busy: !!loading }}
            disabled={inactive}
            onPress={onPress}
            style={({ pressed }) => [
                styles.base,
                {
                    backgroundColor: palette.bg,
                    borderColor: palette.bg,
                    opacity: inactive
                        ? opacity.disabled
                        : pressed
                          ? opacity.pressed
                          : opacity.opaque,
                },
            ]}
        >
            {loading ? (
                <ActivityIndicator color={palette.fg} accessibilityLabel={strings.loading} />
            ) : null}
            <Text style={{ color: palette.fg }} bold>
                {label}
            </Text>
        </Pressable>
    );
}

const styles = StyleSheet.create({
    base: {
        minHeight: minTouchTarget,
        minWidth: minTouchTarget,
        borderRadius: radius.md,
        borderWidth: borderWidth.hairline,
        paddingHorizontal: spacing.lg,
        paddingVertical: spacing.sm,
        flexDirection: "row",
        alignItems: "center",
        justifyContent: "center",
        gap: spacing.sm,
    },
});
