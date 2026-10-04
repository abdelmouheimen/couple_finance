import { useRouter } from "expo-router";
import { useRef, useState } from "react";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { ApiError } from "@/shared/api/problem";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { Button, EmptyState, ErrorState, Screen, Skeleton, Text, useToast } from "@/shared/ui";
import { updateExpense } from "./expenseApi";
import {
    useCategories,
    useCurrentUserId,
    useExpense,
    useInvalidateExpenses,
} from "./expenseQueries";
import { buildUpdateRequest, type ExpenseFormValues } from "./expenseRules";
import { ExpenseForm } from "./ExpenseForm";

/** Edit with If-Match (BR-EXP-12): a 412 asks to reload and never overwrites silently. */
export function EditExpenseScreen({ id }: { id: string }) {
    const router = useRouter();
    const toast = useToast();
    const { household, readOnly } = useHouseholdContext();
    const expense = useExpense(id);
    const categories = useCategories(true);
    const me = useCurrentUserId();
    const invalidate = useInvalidateExpenses();
    const inFlight = useRef(false);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<unknown>(undefined);

    const close = () => router.back();

    if (readOnly) {
        return (
            <Screen onClose={close}>
                <Text tone="danger" bold>
                    {strings.errors.readOnly}
                </Text>
            </Screen>
        );
    }
    if (expense.isPending || categories.isPending || me.isPending) {
        return (
            <Screen onClose={close}>
                <Skeleton variant="card" />
            </Screen>
        );
    }
    if (expense.isError && expense.error instanceof ApiError && expense.error.status === 404) {
        return (
            <Screen onClose={close}>
                <EmptyState
                    title={strings.expense.notFoundTitle}
                    message={strings.expense.notFoundMessage}
                    actionLabel={strings.expense.back}
                    onAction={close}
                />
            </Screen>
        );
    }
    if (expense.isError || categories.isError || me.isError) {
        return (
            <Screen onClose={close}>
                <ErrorState
                    message={errorMessage(expense.error ?? categories.error ?? me.error)}
                    onRetry={() => {
                        void expense.refetch();
                        void categories.refetch();
                        void me.refetch();
                    }}
                />
            </Screen>
        );
    }

    const current = expense.data;
    const conflict = error instanceof ApiError && error.code === "VERSION_CONFLICT";

    const submit = async (values: ExpenseFormValues) => {
        if (inFlight.current) return;
        inFlight.current = true;
        setSubmitting(true);
        setError(undefined);
        try {
            await updateExpense(
                id,
                current.version,
                buildUpdateRequest(current, values, household.currency),
            );
            await invalidate();
            toast(strings.expense.updated);
            close();
        } catch (e) {
            setError(e);
        } finally {
            inFlight.current = false;
            setSubmitting(false);
        }
    };

    return (
        <Screen onClose={close}>
            <Text variant="headline" accessibilityRole="header">
                {strings.expense.editTitle}
            </Text>
            {conflict ? (
                <>
                    <Text tone="danger" bold accessibilityRole="alert">
                        {strings.errors.conflict}
                    </Text>
                    <Button
                        label={strings.expense.reload}
                        onPress={() => {
                            setError(undefined);
                            void expense.refetch();
                        }}
                    />
                </>
            ) : (
                <ExpenseForm
                    // Remount on a new server version so the form shows the reloaded data.
                    key={current.version}
                    expense={current}
                    currency={household.currency}
                    timezone={household.timezone}
                    categories={categories.data}
                    me={me.data}
                    submitLabel={strings.expense.saveChanges}
                    submitting={submitting}
                    serverError={error}
                    onSubmit={(v) => void submit(v)}
                />
            )}
        </Screen>
    );
}
