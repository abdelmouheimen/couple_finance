import { Pressable, StyleSheet } from "react-native";
import { Icon } from "./Icon";
import { type IconName } from "./theme/icons";
import { useColors } from "./theme/theme";
import { borderWidth, minTouchTarget, opacity, radius, size } from "./theme/tokens";

interface Props {
    icon: IconName;
    /** Required: an icon-only control must have an accessible name. */
    label: string;
    onPress: () => void;
    /** `plain` has no container (screen headers); `filled` is the bordered square of the design. */
    variant?: "filled" | "plain";
    disabled?: boolean;
    testID?: string;
}

export function IconButton({ icon, label, onPress, variant = "filled", disabled, testID }: Props) {
    const colors = useColors();
    return (
        <Pressable
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={label}
            accessibilityState={{ disabled: !!disabled }}
            disabled={disabled}
            onPress={onPress}
            style={({ pressed }) => [
                styles.base,
                variant === "filled"
                    ? {
                          backgroundColor: colors.card,
                          borderColor: colors.border,
                          borderWidth: borderWidth.hairline,
                      }
                    : null,
                {
                    opacity: disabled
                        ? opacity.disabled
                        : pressed
                          ? opacity.pressed
                          : opacity.opaque,
                },
            ]}
        >
            <Icon name={icon} />
        </Pressable>
    );
}

const styles = StyleSheet.create({
    base: {
        height: Math.max(size.controlMd, minTouchTarget),
        width: Math.max(size.controlMd, minTouchTarget),
        borderRadius: radius.md,
        alignItems: "center",
        justifyContent: "center",
    },
});
