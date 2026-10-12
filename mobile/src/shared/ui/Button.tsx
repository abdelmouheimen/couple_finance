import { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, Pressable, StyleSheet } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { type IconName } from "./theme/icons";
import { useColors } from "./theme/theme";
import { borderWidth, minTouchTarget, opacity, radius, size, spacing } from "./theme/tokens";

export type ButtonVariant = "primary" | "secondary" | "outline" | "destructive";

interface Props {
    label: string;
    /** May return a promise: further presses are ignored until it settles (double-submit protection). */
    onPress: () => void | Promise<unknown>;
    variant?: ButtonVariant;
    /** `lg` is the full-width call to action at the bottom of a screen. */
    size?: "md" | "lg";
    icon?: IconName;
    loading?: boolean;
    disabled?: boolean;
    testID?: string;
}

export function Button({
    label,
    onPress,
    variant = "primary",
    size: buttonSize = "md",
    icon,
    loading,
    disabled,
    testID,
}: Props) {
    const colors = useColors();
    const palette = {
        primary: { bg: colors.primary, fg: colors.onPrimary, border: colors.primary },
        secondary: { bg: colors.card, fg: colors.text, border: colors.border },
        outline: { bg: colors.background, fg: colors.primary, border: colors.primary },
        destructive: { bg: colors.danger, fg: colors.onDanger, border: colors.danger },
    }[variant];
    const inFlight = useRef(false);
    const mounted = useRef(true);
    useEffect(() => {
        mounted.current = true;
        return () => {
            mounted.current = false;
        };
    }, []);
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
                if (mounted.current) setPending(false);
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
                buttonSize === "lg" ? styles.large : null,
                {
                    backgroundColor: palette.bg,
                    borderColor: palette.border,
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
            ) : icon ? (
                <Icon name={icon} color={palette.fg} />
            ) : null}
            <Text
                variant={buttonSize === "lg" ? "body" : "caption"}
                weight="semibold"
                style={{ color: palette.fg }}
            >
                {label}
            </Text>
        </Pressable>
    );
}

const styles = StyleSheet.create({
    base: {
        minHeight: Math.max(size.controlMd, minTouchTarget),
        minWidth: minTouchTarget,
        borderRadius: radius.pill,
        borderWidth: borderWidth.hairline,
        paddingHorizontal: spacing.xl,
        paddingVertical: spacing.sm,
        flexDirection: "row",
        alignItems: "center",
        justifyContent: "center",
        gap: spacing.sm,
    },
    large: { minHeight: size.controlLg },
});
