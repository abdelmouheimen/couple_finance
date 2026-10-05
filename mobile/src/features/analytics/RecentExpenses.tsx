import { useRouter } from "expo-router";
import { View } from "react-native";
import { ExpenseRow } from "@/features/expense/ExpenseRow";
import type { ExpenseScope } from "@/features/expense/expenseApi";
import { useRecentExpenses } from "@/features/expense/expenseQueries";
import { categoryLabel } from "@/features/expense/expenseRules";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { Button, Card, ErrorState, Skeleton, Text } from "@/shared/ui";
import type { Category } from "./analyticsApi";
import { displayDate } from "./displayDate";

/** Rows previewed on Home (a presentational cut of the server page; the full list is one tap away). */
const RECENT_COUNT = 5;

/** Home block 6: the latest expenses of the period for the active scope, straight from the expense API. */
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
    const names = new Map(categories.map((c) => [c.id, categoryLabel(c)]));
    const items = query.data?.items.slice(0, RECENT_COUNT) ?? [];

    return (
        <Card>
            <Text variant="title" accessibilityRole="header">
                {strings.dashboard.recentTitle}
            </Text>
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
                <View>
                    {items.map((expense) => {
                        const category = names.get(expense.items[0]?.categoryId ?? "");
                        return (
                            <ExpenseRow
                                key={expense.id}
                                expense={expense}
                                title={expense.merchant ?? category ?? strings.expense.untitled}
                                dateLabel={displayDate(expense.date)}
                                categoryLabel={category}
                                onPress={() => router.push(`/expense/${expense.id}`)}
                            />
                        );
                    })}
                </View>
            )}
            <Button
                label={strings.dashboard.seeAllExpenses}
                variant="secondary"
                onPress={() =>
                    router.push({
                        pathname: "/expenses",
                        params: { periodStart, scope, nav: String(Date.now()) },
                    })
                }
            />
        </Card>
    );
}
