import { useRouter } from "expo-router";
import { Pressable, StyleSheet, View } from "react-native";
import { categoryVisual } from "@/features/expense/categoryVisual";
import { ExpenseRow } from "@/features/expense/ExpenseRow";
import type { ExpenseScope } from "@/features/expense/expenseApi";
import { useRecentExpenses } from "@/features/expense/expenseQueries";
import { categoryLabel } from "@/features/expense/expenseRules";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { ErrorState, SectionTitle, Skeleton, Text } from "@/shared/ui";
import { minTouchTarget, spacing } from "@/shared/ui/theme/tokens";
import type { Category } from "./analyticsApi";
import { displayDate } from "./displayDate";

/** Rows previewed on Home (a presentational cut of the server page; the full list is one tap away). */
const RECENT_COUNT = 5;

/** Home: the latest expenses of the period for the active scope, straight from the expense API. */
export function RecentExpenses({
    scope,
    periodStart,
    periodEnd,
    categories,
}: {
    scope: ExpenseScope;
    periodStart: string;
    /** Exclusive end of the period; the API range is inclusive, so the caller passes the last day. */
    periodEnd: string;
    categories: readonly Category[];
}) {
    const router = useRouter();
    const query = useRecentExpenses({ scope, dateFrom: periodStart, dateTo: periodEnd });
    const byId = new Map(categories.map((c) => [c.id, c]));
    const items = query.data?.items.slice(0, RECENT_COUNT) ?? [];

    return (
        <View style={styles.block}>
            <SectionTitle
                action={
                    <Pressable
                        accessibilityRole="button"
                        accessibilityLabel={strings.dashboard.seeAllExpensesA11y}
                        onPress={() =>
                            router.push({
                                pathname: "/expenses",
                                params: { periodStart, scope, nav: String(Date.now()) },
                            })
                        }
                        style={styles.link}
                    >
                        <Text variant="caption" tone="primary" weight="semibold">
                            {strings.dashboard.seeAllExpenses}
                        </Text>
                    </Pressable>
                }
            >
                {strings.dashboard.recentTitle}
            </SectionTitle>
            {query.isError ? (
                <ErrorState
                    message={errorMessage(query.error)}
                    onRetry={() => void query.refetch()}
                />
            ) : !query.data ? (
                <Skeleton variant="row" />
            ) : items.length === 0 ? (
                <Text tone="secondary">{strings.dashboard.recentEmpty}</Text>
            ) : (
                <View style={styles.rows}>
                    {items.map((expense) => {
                        const category = byId.get(expense.items[0]?.categoryId ?? "");
                        const label = category ? categoryLabel(category) : undefined;
                        return (
                            <ExpenseRow
                                key={expense.id}
                                expense={expense}
                                title={expense.merchant ?? label ?? strings.expense.untitled}
                                dateLabel={displayDate(expense.date)}
                                categoryLabel={label}
                                visual={categoryVisual(category)}
                                showDate
                                onPress={() => router.push(`/expense/${expense.id}`)}
                            />
                        );
                    })}
                </View>
            )}
        </View>
    );
}

const styles = StyleSheet.create({
    block: { gap: spacing.md },
    rows: { gap: spacing.sm },
    link: { minHeight: minTouchTarget, justifyContent: "center" },
});
