import { Pressable, StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { formatMoney, spokenMoney } from "@/shared/money/money";
import { IconTile, Text } from "@/shared/ui";
import { useColors } from "@/shared/ui/theme/theme";
import { borderWidth, minTouchTarget, radius, size, spacing } from "@/shared/ui/theme/tokens";
import { type CategoryVisual, categoryVisual } from "./categoryVisual";
import type { Expense } from "./expenseApi";
import { toMoney } from "./expenseRules";

interface Props {
    expense: Expense;
    title: string;
    dateLabel: string;
    categoryLabel?: string | undefined;
    /** Icon and colour of the expense's first category. */
    visual?: CategoryVisual;
    /** Shows the date before the category ("4 oct. 2026 · Courses") when rows are not grouped by date. */
    showDate?: boolean;
    onPress: () => void;
}

/** One expense card. The whole row is a single accessible element: merchant, amount, date and scope. */
export function ExpenseRow({
    expense,
    title,
    dateLabel,
    categoryLabel,
    visual = categoryVisual(undefined),
    showDate,
    onPress,
}: Props) {
    const colors = useColors();
    const money = toMoney(expense.amount);
    const personal = expense.sharingType === "PERSONAL";
    const scope = personal ? strings.scope.PERSONAL : strings.scope.HOUSEHOLD;
    const shownTitle = expense.kind === "REFUND" ? `${strings.expense.refund}: ${title}` : title;
    const subtitle = [showDate ? dateLabel : undefined, categoryLabel]
        .filter((part) => part !== undefined)
        .join(" · ");
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
            style={[styles.row, { backgroundColor: colors.card }]}
            testID={`expense-row-${expense.id}`}
        >
            <IconTile icon={visual.icon} color={visual.color} />
            <View style={styles.main}>
                <Text weight="medium" numberOfLines={1}>
                    {shownTitle}
                </Text>
                {subtitle !== "" ? (
                    <Text variant="caption" tone="secondary" numberOfLines={1}>
                        {subtitle}
                    </Text>
                ) : null}
                {personal ? (
                    <View style={[styles.badge, { borderColor: colors.primary }]}>
                        <Text variant="label" tone="primary">
                            {strings.scope.PERSONAL}
                        </Text>
                    </View>
                ) : null}
            </View>
            <Text tabular weight="semibold">
                {formatMoney(money)}
            </Text>
        </Pressable>
    );
}

const styles = StyleSheet.create({
    row: {
        minHeight: Math.max(size.iconTile + spacing.xl, minTouchTarget),
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.md,
        padding: spacing.md,
        borderRadius: radius.lg,
    },
    main: { flex: 1, gap: spacing.xs, alignItems: "flex-start" },
    badge: {
        borderWidth: borderWidth.hairline,
        borderRadius: radius.pill,
        paddingHorizontal: spacing.sm,
    },
});
