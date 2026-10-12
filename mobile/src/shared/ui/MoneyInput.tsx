import { useState } from "react";
import { StyleSheet, TextInput as RNTextInput, type TextInputProps, View } from "react-native";
import { currencyDecimals, currencySymbol } from "@/shared/money/money";
import { Text } from "./Text";
import {
    canonicalAmount,
    decimalSeparator,
    padAmount,
    sanitizeAmount,
    toDisplay,
} from "./moneyInputParsing";
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
    tabularFigures,
} from "./theme/tokens";

type Forwarded = Pick<
    TextInputProps,
    "autoFocus" | "returnKeyType" | "onSubmitEditing" | "placeholder" | "testID"
>;

interface Props extends Forwarded {
    label: string;
    /** Canonical decimal string ("12.50") or "". */
    value: string;
    /** Receives the canonical string only; never a number. */
    onChangeValue: (canonical: string) => void;
    currency: string;
    locale?: string;
    error?: string;
}

export function MoneyInput({
    label,
    value,
    onChangeValue,
    currency,
    locale,
    error,
    autoFocus,
    returnKeyType,
    onSubmitEditing,
    placeholder,
    testID,
}: Props) {
    const colors = useColors();
    const decimals = currencyDecimals(currency);
    const separator = decimalSeparator(locale);
    // Raw text is kept while typing so a trailing separator ("12,") survives re-renders.
    const [text, setText] = useState(toDisplay(value, separator));
    const shown = canonicalAmount(text, decimals) === value ? text : toDisplay(value, separator);

    return (
        <View style={styles.field}>
            <Text variant="caption" tone="secondary">
                {label}
            </Text>
            <View
                style={[
                    styles.row,
                    {
                        backgroundColor: colors.card,
                        borderColor: error ? colors.danger : colors.border,
                    },
                ]}
            >
                <RNTextInput
                    accessibilityLabel={`${label} (${currency})`}
                    accessibilityHint={error}
                    autoFocus={autoFocus}
                    returnKeyType={returnKeyType}
                    onSubmitEditing={onSubmitEditing}
                    placeholder={placeholder ?? toDisplay(padAmount("0", decimals), separator)}
                    placeholderTextColor={colors.textSecondary}
                    testID={testID}
                    keyboardType={decimals > 0 ? "decimal-pad" : "number-pad"}
                    maxFontSizeMultiplier={maxFontScale}
                    value={shown}
                    onChangeText={(raw) => {
                        setText(toDisplay(sanitizeAmount(raw, decimals), separator));
                        onChangeValue(canonicalAmount(raw, decimals));
                    }}
                    onBlur={() => {
                        const padded = padAmount(canonicalAmount(text, decimals), decimals);
                        setText(toDisplay(padded, separator));
                        onChangeValue(padded);
                    }}
                    style={[styles.input, { color: colors.text }]}
                />
                <View style={[styles.suffix, { borderLeftColor: colors.border }]}>
                    <Text weight="semibold">{currencySymbol(currency, locale)}</Text>
                </View>
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
    row: {
        minHeight: Math.max(size.field, minTouchTarget),
        flexDirection: "row",
        alignItems: "stretch",
        borderWidth: borderWidth.hairline,
        borderRadius: radius.md,
    },
    input: {
        flex: 1,
        minHeight: minTouchTarget,
        paddingHorizontal: spacing.lg,
        paddingVertical: spacing.none,
        fontSize: fontSize.body,
        fontFamily: fontFamily.medium,
        fontVariant: tabularFigures,
    },
    suffix: {
        minWidth: size.field,
        paddingHorizontal: spacing.md,
        alignItems: "center",
        justifyContent: "center",
        borderLeftWidth: borderWidth.hairline,
    },
});
