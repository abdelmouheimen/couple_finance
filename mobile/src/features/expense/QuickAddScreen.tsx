import { useRouter } from "expo-router";
import { useRef, useState } from "react";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { ErrorState, Screen, Skeleton, Text, useToast } from "@/shared/ui";
import { createExpense } from "./expenseApi";
import { useCategories, useCurrentUserId, useInvalidateExpenses } from "./expenseQueries";
import { buildCreateRequest, type ExpenseFormValues, IdempotencyKeys } from "./expenseRules";
import { ExpenseForm } from "./ExpenseForm";

/** Keyboard-first quick add: amount -> category -> Save, with the approved defaults prefilled. */
export function QuickAddScreen({ now }: { now?: () => Date }) {
    const router = useRouter();
    const toast = useToast();
    const { household, readOnly } = useHouseholdContext();
    const categories = useCategories(false);
    const me = useCurrentUserId();
    const invalidate = useInvalidateExpenses();
    const keys = useRef(new IdempotencyKeys());
    const inFlight = useRef(false);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<unknown>(undefined);

    const close = () => router.back();

    if (readOnly) {
        return (
            <Screen edges={["top", "left", "right", "bottom"]} onClose={close}>
                <Text tone="danger" bold>
                    {strings.errors.readOnly}
                </Text>
            </Screen>
        );
    }

    const submit = async (values: ExpenseFormValues) => {
        if (inFlight.current || !me.data) return; // a double tap sends exactly one request
        inFlight.current = true;
        setSubmitting(true);
        setError(undefined);
        try {
            const body = buildCreateRequest(values, {
                currency: household.currency,
                paidByUserId: me.data,
            });
            // Same payload -> same key: a retry after a network failure can never duplicate (BR-EXP-13).
            const created = await createExpense(body, keys.current.keyFor(body));
            keys.current.reset();
            await invalidate();
            toast(strings.expense.saved, "success", {
                label: strings.expense.view,
                onPress: () => router.push(`/expense/${created.id}`),
            });
            close();
        } catch (e) {
            setError(e);
        } finally {
            inFlight.current = false;
            setSubmitting(false);
        }
    };

    return (
        <Screen edges={["top", "left", "right", "bottom"]} onClose={close}>
            <Text variant="headline" accessibilityRole="header">
                {strings.addExpense}
            </Text>
            {categories.isPending || me.isPending ? (
                <Skeleton variant="card" />
            ) : categories.isError || me.isError ? (
                <ErrorState
                    message={errorMessage(categories.error ?? me.error)}
                    onRetry={() => {
                        void categories.refetch();
                        void me.refetch();
                    }}
                />
            ) : (
                <ExpenseForm
                    currency={household.currency}
                    timezone={household.timezone}
                    categories={categories.data}
                    me={me.data}
                    submitLabel={strings.expense.save}
                    submitting={submitting}
                    serverError={error}
                    {...(now ? { now } : {})}
                    onSubmit={(v) => void submit(v)}
                />
            )}
        </Screen>
    );
}
