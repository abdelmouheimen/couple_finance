import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { useRef, useState } from "react";
import { StyleSheet, View } from "react-native";
import { ExpenseForm } from "@/features/expense/ExpenseForm";
import {
    useCategories,
    useCurrentUserId,
    useInvalidateExpenses,
} from "@/features/expense/expenseQueries";
import { type ExpenseFormValues, IdempotencyKeys } from "@/features/expense/expenseRules";
import { formatDate } from "@/features/expense/ExpenseListScreen";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { ApiError } from "@/shared/api/problem";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { formatMoney } from "@/shared/money/money";
import {
    Button,
    Card,
    ConfirmSheet,
    EmptyState,
    ErrorState,
    Screen,
    Skeleton,
    Text,
    useToast,
} from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import {
    attachReceipt,
    buildConfirmRequest,
    confirmReceipt,
    getReceiptReview,
    type ReceiptDuplicate,
    type ReceiptReview,
    RECEIPTS_KEY,
} from "./receiptApi";

const edges = ["top", "left", "right", "bottom"] as const;

function fieldLabel(field: string): string {
    return strings.receipt.review.fields[field] ?? field;
}

/** One line per field the member has to check, with what was printed and the possible readings. */
function unresolvedLine(review: ReceiptReview, field: string): string {
    const value = review.fields[field];
    const status =
        strings.receipt.review.status[value?.status ?? ""] ?? strings.receipt.review.check;
    const printed = value?.raw ? ` ${strings.receipt.review.printed(value.raw)}` : "";
    const candidates =
        value && value.candidates.length > 0
            ? ` ${strings.receipt.review.candidates(value.candidates.join(" · "))}`
            : "";
    return `${fieldLabel(field)} : ${status}.${printed}${candidates}`;
}

/**
 * "Vérifier les informations" wired to the API (DOC-004). The analysis only proposes: the form is prefilled with
 * the unambiguous values, every other field is flagged, and nothing becomes an expense until the member has
 * explicitly acknowledged the total and the date (BR-RCP-02, BR-RCP-10). The amount stays a string end to end; the
 * device computes nothing. Potential duplicates are warnings: the member can still confirm, or attach the receipt
 * to the existing expense (BR-RCP-14).
 */
export function ReceiptReviewContainer({ id, now }: { id: string; now?: () => Date }) {
    const router = useRouter();
    const toast = useToast();
    const queryClient = useQueryClient();
    const { household, readOnly } = useHouseholdContext();
    const categories = useCategories(false);
    const me = useCurrentUserId();
    const invalidateExpenses = useInvalidateExpenses();
    const keys = useRef(new IdempotencyKeys());
    const inFlight = useRef(false);
    const [pending, setPending] = useState<ExpenseFormValues | null>(null);
    const [attaching, setAttaching] = useState<ReceiptDuplicate | null>(null);
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<unknown>(undefined);
    const review = useQuery({
        queryKey: [...RECEIPTS_KEY, "review", id],
        queryFn: () => getReceiptReview(id),
        retry: false,
        // The version sent back must be the one displayed: never serve a cached review.
        gcTime: 0,
        staleTime: 0,
    });
    const back = () => router.back();
    const title = strings.receipt.reviewTitle;

    if (readOnly) {
        return (
            <Screen edges={edges} title={title} onBack={back}>
                <Text tone="danger" weight="semibold">
                    {strings.errors.readOnly}
                </Text>
            </Screen>
        );
    }
    if (review.isPending || categories.isPending || me.isPending) {
        return (
            <Screen edges={edges} title={title} onBack={back}>
                <Skeleton variant="card" />
            </Screen>
        );
    }
    if (review.isError || categories.isError || me.isError) {
        const failure = review.error ?? categories.error ?? me.error;
        const notFound = failure instanceof ApiError && failure.status === 404;
        return (
            <Screen edges={edges} title={title} onBack={back}>
                {notFound ? (
                    <EmptyState
                        title={strings.errors.receiptNotFound}
                        message={strings.receipt.review.notReviewable}
                    />
                ) : (
                    <ErrorState
                        message={errorMessage(failure)}
                        onRetry={() => {
                            void review.refetch();
                            void categories.refetch();
                            void me.refetch();
                        }}
                    />
                )}
            </Screen>
        );
    }

    const data = review.data;
    const sameCurrency = !data.proposedTotal || data.proposedTotal.currency === household.currency;
    const initial: Partial<ExpenseFormValues> = {
        ...(data.proposedTotal?.amount && sameCurrency
            ? { amount: data.proposedTotal.amount }
            : {}),
        ...(data.proposedDate ? { date: data.proposedDate } : {}),
        ...(data.proposedMerchant ? { merchant: data.proposedMerchant } : {}),
        ...(data.suggestedCategory ? { categoryId: data.suggestedCategory.categoryId } : {}),
    };

    const linked = async (expenseId: string, message: string) => {
        keys.current.reset();
        await invalidateExpenses();
        await queryClient.invalidateQueries({ queryKey: RECEIPTS_KEY });
        toast(message, "success");
        router.replace(`/expense/${expenseId}`);
    };

    const run = async (action: () => Promise<void>) => {
        if (inFlight.current) return; // a double tap sends exactly one request
        inFlight.current = true;
        setSubmitting(true);
        setError(undefined);
        try {
            await action();
        } catch (e) {
            setError(e);
            // The receipt changed (e.g. the analysis just finished): show its current state.
            if (e instanceof ApiError && e.code === "RECEIPT_VERSION_CONFLICT")
                void review.refetch();
        } finally {
            inFlight.current = false;
            setSubmitting(false);
        }
    };

    const confirm = (values: ExpenseFormValues) =>
        run(async () => {
            const body = buildConfirmRequest(values, data, {
                currency: household.currency,
                paidByUserId: me.data,
            });
            // Same payload -> same key: a retry after a network failure never creates a second expense.
            const link = await confirmReceipt(id, body, keys.current.keyFor(body));
            await linked(link.expenseId, strings.receipt.review.created);
        });

    const attach = (duplicate: ReceiptDuplicate) =>
        run(async () => {
            if (!duplicate.expenseId) return;
            const body = { expenseId: duplicate.expenseId, version: data.version };
            const link = await attachReceipt(id, body, keys.current.keyFor(body));
            await linked(link.expenseId, strings.receipt.review.attached);
        });

    return (
        <Screen edges={edges} title={title} onBack={back}>
            <Card>
                <Text variant="caption" tone="secondary" testID="review-notice">
                    {data.analysed
                        ? strings.receipt.review.proposalNotice
                        : strings.receipt.review.manualNotice}
                </Text>
                {data.refundProposed ? (
                    <Text variant="caption" tone="warning">
                        {strings.receipt.review.refundNotice}
                    </Text>
                ) : null}
                {data.detectedCurrency && data.detectedCurrency !== household.currency ? (
                    <Text variant="caption" tone="warning">
                        {strings.receipt.review.foreignCurrency(
                            data.detectedCurrency,
                            household.currency,
                        )}
                    </Text>
                ) : null}
            </Card>
            {data.unresolvedFields.length > 0 ? (
                <Card testID="review-unresolved">
                    <Text weight="semibold" tone="warning">
                        {strings.receipt.review.unresolvedTitle}
                    </Text>
                    {data.unresolvedFields.map((field) => (
                        <Text key={field} variant="caption">
                            {unresolvedLine(data, field)}
                        </Text>
                    ))}
                </Card>
            ) : null}
            {data.findings.length > 0 ? (
                <Card testID="review-findings">
                    <Text weight="semibold" tone="warning">
                        {strings.receipt.review.findingsTitle}
                    </Text>
                    {data.findings.map((finding) => (
                        <Text key={finding} variant="caption">
                            {strings.receipt.review.findings[finding] ??
                                strings.receipt.review.findingDefault}
                        </Text>
                    ))}
                </Card>
            ) : null}
            {data.duplicates.map((duplicate) => (
                <Card
                    key={`${duplicate.reason}:${duplicate.expenseId ?? duplicate.receiptId ?? ""}`}
                    testID="review-duplicate"
                >
                    <Text weight="semibold" tone="warning">
                        {strings.receipt.review.duplicateTitle}
                    </Text>
                    <Text variant="caption">
                        {strings.receipt.review.duplicate[duplicate.reason]}
                    </Text>
                    <View style={styles.actions}>
                        {duplicate.expenseId ? (
                            <Button
                                variant="secondary"
                                label={strings.receipt.review.viewExpense}
                                onPress={() => router.push(`/expense/${duplicate.expenseId}`)}
                            />
                        ) : duplicate.receiptId ? (
                            <Button
                                variant="secondary"
                                label={strings.receipt.review.viewReceipt}
                                onPress={() => router.push(`/receipts/${duplicate.receiptId}`)}
                            />
                        ) : null}
                        {duplicate.attachable ? (
                            <Button
                                variant="outline"
                                label={strings.receipt.review.attach}
                                onPress={() => setAttaching(duplicate)}
                            />
                        ) : null}
                    </View>
                </Card>
            ))}
            <ExpenseForm
                currency={household.currency}
                timezone={household.timezone}
                categories={categories.data}
                me={me.data}
                initial={initial}
                submitLabel={strings.receipt.create}
                submitting={submitting}
                serverError={error}
                {...(now ? { now } : {})}
                onSubmit={(values) => {
                    setError(undefined);
                    setPending(values);
                }}
            />
            <ConfirmSheet
                visible={pending !== null}
                title={strings.receipt.review.acknowledgeTitle}
                message={
                    pending
                        ? strings.receipt.review.acknowledgeMessage(
                              formatMoney({ amount: pending.amount, currency: household.currency }),
                              formatDate(pending.date),
                              data.unresolvedFields.map(fieldLabel),
                          )
                        : ""
                }
                confirmLabel={strings.receipt.review.acknowledge}
                confirmVariant="primary"
                onConfirm={() => {
                    const values = pending;
                    setPending(null);
                    if (values) void confirm(values);
                }}
                onCancel={() => setPending(null)}
            />
            <ConfirmSheet
                visible={attaching !== null}
                title={strings.receipt.review.attachTitle}
                message={strings.receipt.review.attachMessage}
                confirmLabel={strings.receipt.review.attach}
                confirmVariant="primary"
                onConfirm={() => {
                    const duplicate = attaching;
                    setAttaching(null);
                    if (duplicate) void attach(duplicate);
                }}
                onCancel={() => setAttaching(null)}
            />
        </Screen>
    );
}

const styles = StyleSheet.create({
    actions: { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
});
