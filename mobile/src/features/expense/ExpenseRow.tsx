import { Pressable, StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney, spokenMoney } from "@/shared/money/money";
import { Text } from "@/shared/ui";
import { useColors } from "@/shared/ui/theme/theme";
import { borderWidth, minTouchTarget, radius, spacing } from "@/shared/ui/theme/tokens";
import type { Expense } from "./expenseApi";
import { toMoney } from "./expenseRules";

interface Props {
    expense: Expense;
    title: string;
    dateLabel: string;
    categoryLabel?: string;
    onPress: () => void;
}

/** One list row. The whole row is a single accessible element: merchant, amount, date and scope. */
export function ExpenseRow({ expense, title, dateLabel, categoryLabel, onPress }: Props) {
    const colors = useColors();
    const money = toMoney(expense.amount);
    const personal = expense.sharingType === "PERSONAL";
    const scope = personal ? strings.scope.PERSONAL : strings.scope.HOUSEHOLD;
    const shownTitle = expense.kind === "REFUND" ? `${strings.expense.refund}: ${title}` : title;
    return (
        <Pressable
            accessibilityRole="button"
            accessibilityLabel={strings.expense.rowLabel(
                shownTitle,
                spokenMoney(money),
                dateLabel,
                scope,
            )}
            onPress={onPress}
            style={styles.row}
            testID={`expense-row-${expense.id}`}
        >
            <View style={styles.main}>
                <Text bold>{shownTitle}</Text>
                {categoryLabel ? (
                    <Text variant="caption" tone="secondary">
                        {categoryLabel}
                    </Text>
                ) : null}
                {personal ? (
                    <View style={[styles.badge, { borderColor: colors.primary }]}>
                        <Text variant="caption" tone="primary" bold>
                            {strings.scope.PERSONAL}
                        </Text>
                    </View>
                ) : null}
            </View>
            <Text variant="body" tabular bold>
                {formatMoney(money)}
            </Text>
        </Pressable>
    );
}

const styles = StyleSheet.create({
    row: {
        minHeight: minTouchTarget,
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.md,
        paddingVertical: spacing.sm,
    },
    main: { flex: 1, gap: spacing.xs, alignItems: "flex-start" },
    badge: {
        borderWidth: borderWidth.hairline,
        borderRadius: radius.pill,
        paddingHorizontal: spacing.sm,
    },
});
