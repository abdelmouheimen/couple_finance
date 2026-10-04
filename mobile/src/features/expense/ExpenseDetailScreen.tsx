import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { useRef, useState } from "react";
import { StyleSheet, View } from "react-native";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { ApiError } from "@/shared/api/problem";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import {
    Button,
    Card,
    ConfirmSheet,
    EmptyState,
    ErrorState,
    ListRow,
    MoneyText,
    Screen,
    Skeleton,
    Text,
    useToast,
} from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import { deleteExpense, type Expense, restoreExpense } from "./expenseApi";
import { EXPENSES_KEY, useCategories, useExpense, useExpenseAudit } from "./expenseQueries";
import { categoryLabel, toMoney } from "./expenseRules";
import { formatDate } from "./ExpenseListScreen";

/** Display only: the instant's calendar day in the household timezone (no date arithmetic). */
function formatInstant(instant: string, timeZone: string): string {
    try {
        return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeZone }).format(
            new Date(instant),
        );
    } catch {
        return formatDate(instant.slice(0, 10));
    }
}

/** Detail: items, merchant, note, audit trail; Edit / Delete (with restore from the toast). */
export function ExpenseDetailScreen({ id }: { id: string }) {
    const router = useRouter();
    const toast = useToast();
    const client = useQueryClient();
    const { readOnly, household } = useHouseholdContext();
    const expense = useExpense(id);
    const categories = useCategories(true);
    const audit = useExpenseAudit(id, expense.isSuccess);
    const [confirming, setConfirming] = useState(false);
    const [deleteError, setDeleteError] = useState<string | undefined>(undefined);
    const deleting = useRef(false);

    const goBack = () => {
        if (router.canGoBack()) router.back();
        else router.replace("/expenses");
    };

    if (expense.isPending) {
        return (
            <Screen onClose={goBack}>
                <Skeleton variant="card" />
            </Screen>
        );
    }
    // 404 covers a missing, deleted, other-household or partner-personal id alike (no existence leak).
    if (expense.isError && expense.error instanceof ApiError && expense.error.status === 404) {
        return (
            <Screen onClose={goBack}>
                <EmptyState
                    title={strings.expense.notFoundTitle}
                    message={strings.expense.notFoundMessage}
                    actionLabel={strings.expense.back}
                    onAction={goBack}
                />
            </Screen>
        );
    }
    if (expense.isError) {
        return (
            <Screen onClose={goBack}>
                <ErrorState
                    message={errorMessage(expense.error)}
                    onRetry={() => void expense.refetch()}
                />
            </Screen>
        );
    }

    const data: Expense = expense.data;
    const names = new Map((categories.data ?? []).map((c) => [c.id, categoryLabel(c)]));

    const confirmDelete = async () => {
        if (deleting.current) return;
        deleting.current = true;
        try {
            await deleteExpense(id);
            setConfirming(false);
            await client.invalidateQueries({ queryKey: EXPENSES_KEY });
            toast(strings.expense.deleted, "success", {
                label: strings.expense.restore,
                onPress: () => {
                    void restoreExpense(id)
                        .then(() => client.invalidateQueries({ queryKey: EXPENSES_KEY }))
                        .then(() => toast(strings.expense.restored))
                        .catch((e: unknown) => toast(errorMessage(e), "error"));
                },
            });
            goBack();
        } catch (e) {
            setConfirming(false);
            setDeleteError(errorMessage(e));
        } finally {
            deleting.current = false;
        }
    };

    return (
        <Screen onClose={goBack}>
            <Text variant="headline" accessibilityRole="header">
                {data.merchant ?? strings.expense.untitled}
            </Text>
            <Card>
                <MoneyText
                    money={toMoney(data.amount)}
                    scope={data.sharingType === "PERSONAL" ? "PERSONAL" : "HOUSEHOLD"}
                />
                <Text tone="secondary">{formatDate(data.date)}</Text>
                {data.kind === "REFUND" ? (
                    <Text variant="caption" tone="secondary" bold>
                        {strings.expense.refund}
                    </Text>
                ) : null}
                {data.note ? <Text>{data.note}</Text> : null}
            </Card>
            <Text variant="title" accessibilityRole="header">
                {strings.expense.items}
            </Text>
            <Card>
                {data.items.map((item) => (
                    <ListRow
                        key={item.id}
                        title={names.get(item.categoryId) ?? strings.expense.untitled}
                        {...(item.label ? { subtitle: item.label } : {})}
                        trailing={<MoneyText money={toMoney(item.amount)} variant="body" />}
                    />
                ))}
            </Card>
            {deleteError ? (
                <Text tone="danger" bold accessibilityRole="alert">
                    {deleteError}
                </Text>
            ) : null}
            {readOnly ? null : (
                <View style={styles.actions}>
                    <Button
                        label={strings.expense.edit}
                        onPress={() => router.push(`/expense/edit/${id}`)}
                    />
                    <Button
                        label={strings.expense.delete}
                        variant="destructive"
                        onPress={() => {
                            setDeleteError(undefined);
                            setConfirming(true);
                        }}
                    />
                </View>
            )}
            <Text variant="title" accessibilityRole="header">
                {strings.expense.history}
            </Text>
            <Card>
                {audit.data?.length ? (
                    audit.data.map((entry) => (
                        <ListRow
                            key={entry.id}
                            title={
                                strings.expense.auditAction[entry.action] ??
                                strings.expense.auditUnknown
                            }
                            subtitle={formatInstant(entry.occurredAt, household.timezone)}
                        />
                    ))
                ) : (
                    <Text tone="secondary">
                        {audit.isPending ? strings.loading : strings.expense.historyEmpty}
                    </Text>
                )}
            </Card>
            <ConfirmSheet
                visible={confirming}
                title={strings.expense.deleteTitle}
                message={strings.expense.deleteMessage}
                confirmLabel={strings.expense.delete}
                onConfirm={() => void confirmDelete()}
                onCancel={() => setConfirming(false)}
            />
        </Screen>
    );
}

const styles = StyleSheet.create({ actions: { gap: spacing.sm } });
