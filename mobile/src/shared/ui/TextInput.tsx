import { type ReactNode } from "react";
import { StyleSheet, TextInput as RNTextInput, type TextInputProps, View } from "react-native";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { type IconName } from "./theme/icons";
import { useColors } from "./theme/theme";
import {
    borderWidth,
    fontFamily,
    fontSize,
    maxFontScale,
    minTouchTarget,
    radius,
    size,
    spacing,
} from "./theme/tokens";

export interface FieldProps extends Omit<TextInputProps, "style"> {
    label: string;
    /** Inline validation message (already human-readable). */
    error?: string;
    /** Outline icon inside the field, before the text. */
    icon?: IconName;
    /** Element inside the field, after the text (e.g. a confirmation check). */
    trailing?: ReactNode;
    /** Search fields carry their label for assistive tech only. */
    hideLabel?: boolean;
}

export function TextInput({ label, error, icon, trailing, hideLabel, ...rest }: FieldProps) {
    const colors = useColors();
    return (
        <View style={styles.field}>
            {hideLabel ? null : (
                <Text variant="caption" tone="secondary">
                    {label}
                </Text>
            )}
            <View
                style={[
                    styles.box,
                    hideLabel ? styles.search : null,
                    rest.multiline ? styles.multiline : null,
                    {
                        backgroundColor: colors.card,
                        borderColor: error ? colors.danger : colors.border,
                    },
                ]}
            >
                {icon ? <Icon name={icon} color={colors.textSecondary} /> : null}
                <RNTextInput
                    accessibilityLabel={label}
                    accessibilityHint={error}
                    placeholderTextColor={colors.textSecondary}
                    maxFontSizeMultiplier={maxFontScale}
                    {...rest}
                    style={[styles.input, { color: colors.text }]}
                />
                {trailing}
            </View>
            {error ? (
                <Text variant="caption" tone="danger" accessibilityLiveRegion="polite">
                    {error}
                </Text>
            ) : null}
        </View>
    );
}

const styles = StyleSheet.create({
    field: { gap: spacing.sm },
    box: {
        minHeight: Math.max(size.field, minTouchTarget),
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.md,
        borderWidth: borderWidth.hairline,
        borderRadius: radius.md,
        paddingHorizontal: spacing.lg,
    },
    search: { minHeight: Math.max(size.controlMd, minTouchTarget) },
    multiline: { alignItems: "flex-start", paddingVertical: spacing.md },
    input: {
        flex: 1,
        minHeight: minTouchTarget,
        paddingVertical: spacing.none,
        fontSize: fontSize.body,
        fontFamily: fontFamily.regular,
    },
});
