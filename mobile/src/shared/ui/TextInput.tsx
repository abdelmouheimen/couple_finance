import { StyleSheet, TextInput as RNTextInput, type TextInputProps, View } from "react-native";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import {
    borderWidth,
    fontSize,
    maxFontScale,
    minTouchTarget,
    radius,
    spacing,
} from "./theme/tokens";

export interface FieldProps extends Omit<TextInputProps, "style"> {
    label: string;
    /** Inline validation message (already human-readable). */
    error?: string;
}

export function TextInput({ label, error, ...rest }: FieldProps) {
    const colors = useColors();
    return (
        <View style={styles.field}>
            <Text variant="caption" tone="secondary" bold>
                {label}
            </Text>
            <RNTextInput
                accessibilityLabel={label}
                accessibilityHint={error}
                placeholderTextColor={colors.textSecondary}
                maxFontSizeMultiplier={maxFontScale}
                {...rest}
                style={[
                    styles.input,
                    {
                        color: colors.text,
                        backgroundColor: colors.surface,
                        borderColor: error ? colors.danger : colors.border,
                    },
                ]}
            />
            {error ? (
                <Text variant="caption" tone="danger" accessibilityLiveRegion="polite">
                    {error}
                </Text>
            ) : null}
        </View>
    );
}

const styles = StyleSheet.create({
    field: { gap: spacing.xs },
    input: {
        minHeight: minTouchTarget,
        borderWidth: borderWidth.hairline,
        borderRadius: radius.md,
        paddingHorizontal: spacing.md,
        paddingVertical: spacing.sm,
        fontSize: fontSize.body,
    },
});
