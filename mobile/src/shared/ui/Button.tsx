import { useCallback, useRef, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { borderWidth, minTouchTarget, opacity, radius, spacing } from "./theme/tokens";

export type ButtonVariant = "primary" | "secondary" | "destructive";

interface Props {
    label: string;
    /** May return a promise: further presses are ignored until it settles (double-submit protection). */
    onPress: () => void | Promise<unknown>;
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
    const inFlight = useRef(false);
    const [pending, setPending] = useState(false);
    const busy = !!loading || pending;
    const inactive = disabled || busy;
    // Guard synchronously (a ref, not state) so two taps in the same frame cannot both fire.
    const press = useCallback(() => {
        if (inFlight.current) return;
        const result = onPress();
        if (result && typeof (result as Promise<unknown>).then === "function") {
            inFlight.current = true;
            setPending(true);
            const release = () => {
                inFlight.current = false;
                setPending(false);
            };
            (result as Promise<unknown>).then(release, release);
        }
    }, [onPress]);
    return (
        <Pressable
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={label}
            accessibilityState={{ disabled: !!inactive, busy }}
            disabled={inactive}
            onPress={press}
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
            {busy ? (
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
