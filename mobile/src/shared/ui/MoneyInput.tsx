import { useState } from "react";
import { StyleSheet, TextInput as RNTextInput, View } from "react-native";
import { currencyDecimals } from "@/shared/money/money";
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
    fontSize,
    maxFontScale,
    minTouchTarget,
    radius,
    spacing,
    tabularFigures,
} from "./theme/tokens";

interface Props {
    label: string;
    /** Canonical decimal string ("12.50") or "". */
    value: string;
    /** Receives the canonical string only; never a number. */
    onChangeValue: (canonical: string) => void;
    currency: string;
    locale?: string;
    error?: string;
}

export function MoneyInput({ label, value, onChangeValue, currency, locale, error }: Props) {
    const colors = useColors();
    const decimals = currencyDecimals(currency);
    const separator = decimalSeparator(locale);
    // Raw text is kept while typing so a trailing separator ("12,") survives re-renders.
    const [text, setText] = useState(toDisplay(value, separator));
    const shown = canonicalAmount(text, decimals) === value ? text : toDisplay(value, separator);

    return (
        <View style={styles.field}>
            <Text variant="caption" tone="secondary" bold>
                {label}
            </Text>
            <View
                style={[
                    styles.row,
                    {
                        backgroundColor: colors.surface,
                        borderColor: error ? colors.danger : colors.border,
                    },
                ]}
            >
                <RNTextInput
                    accessibilityLabel={`${label} (${currency})`}
                    accessibilityHint={error}
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
                <Text bold tone="secondary">
                    {currency}
                </Text>
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
    field: { gap: spacing.xs },
    row: {
        minHeight: minTouchTarget,
        flexDirection: "row",
        alignItems: "center",
        borderWidth: borderWidth.hairline,
        borderRadius: radius.md,
        paddingHorizontal: spacing.md,
        gap: spacing.sm,
    },
    input: {
        flex: 1,
        minHeight: minTouchTarget,
        fontSize: fontSize.amount,
        fontVariant: tabularFigures,
    },
});
