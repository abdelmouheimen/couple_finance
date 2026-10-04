import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney, type Money } from "@/shared/money/money";
import { Text, type TextVariant } from "./Text";
import { spacing } from "./theme/tokens";

export type Scope = "HOUSEHOLD" | "PERSONAL";

interface Props {
    money: Money;
    /** BR-SCP-03: every total is labelled with its scope. */
    scope?: Scope;
    variant?: TextVariant;
    locale?: string;
}

/** Renders an API Money value (string amount + explicit currency) with tabular figures. */
export function MoneyText({ money, scope, variant = "amount", locale }: Props) {
    const formatted = formatMoney(money, locale);
    const scopeLabel = scope ? strings.scope[scope] : undefined;
    return (
        <View
            accessible
            accessibilityLabel={scopeLabel ? `${formatted}, ${scopeLabel}` : formatted}
            style={styles.wrap}
        >
            <Text variant={variant} tabular>
                {formatted}
            </Text>
            {scopeLabel ? (
                <Text variant="caption" tone="secondary" bold>
                    {scopeLabel}
                </Text>
            ) : null}
        </View>
    );
}

const styles = StyleSheet.create({ wrap: { gap: spacing.xs } });
