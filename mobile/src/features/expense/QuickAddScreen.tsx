import { useRouter } from "expo-router";
import { useRef, useState } from "react";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import {
    RECEIPT_ANALYSIS_AVAILABLE,
    ReceiptCapturePanel,
} from "@/features/receipt/ReceiptCapturePanel";
import { RECEIPTS_KEY } from "@/features/receipt/receiptApi";
import { useQueryClient } from "@tanstack/react-query";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { ErrorState, Screen, Segmented, Skeleton, Text, useToast } from "@/shared/ui";
import { createExpense } from "./expenseApi";
import { useCategories, useCurrentUserId, useInvalidateExpenses } from "./expenseQueries";
import { buildCreateRequest, type ExpenseFormValues, IdempotencyKeys } from "./expenseRules";
import { ExpenseForm } from "./ExpenseForm";

type Mode = "SCAN" | "MANUAL" | "IMPORT";

const MODES: readonly { value: Mode; label: string }[] = [
    { value: "SCAN", label: strings.expense.modes.SCAN },
    { value: "MANUAL", label: strings.expense.modes.MANUAL },
    { value: "IMPORT", label: strings.expense.modes.IMPORT },
];

/**
 * "Ajouter une dépense": Scanner / Manuel / Importer, as in the approved design. Manual entry is the
 * keyboard-first quick add (amount -> category -> Save, approved defaults prefilled). It still opens on
 * manual entry (the design default is "Scanner"): switching the default entry mode is a product decision left to
 * the Tech Lead. A scanned or imported receipt is stored in the vault, analysed when the member consented, and
 * becomes an expense only through its review ("Vérifier et créer la dépense").
 */
export function QuickAddScreen({ now }: { now?: () => Date }) {
    const router = useRouter();
    const toast = useToast();
    const { household, readOnly } = useHouseholdContext();
    const categories = useCategories(false);
    const me = useCurrentUserId();
    const invalidate = useInvalidateExpenses();
    const queryClient = useQueryClient();
    const keys = useRef(new IdempotencyKeys());
    const inFlight = useRef(false);
    const [mode, setMode] = useState<Mode>(RECEIPT_ANALYSIS_AVAILABLE ? "SCAN" : "MANUAL");
    const [submitting, setSubmitting] = useState(false);
    const [error, setError] = useState<unknown>(undefined);

    const close = () => router.back();
    const edges = ["top", "left", "right", "bottom"] as const;

    if (readOnly) {
        return (
            <Screen edges={edges} title={strings.addExpense} onClose={close}>
                <Text tone="danger" weight="semibold">
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
        <Screen edges={edges} title={strings.addExpense} onClose={close}>
            <Segmented
                testID="entry-mode"
                label={strings.expense.entryMode}
                options={MODES}
                value={mode}
                onChange={setMode}
            />
            {mode !== "MANUAL" ? (
                <ReceiptCapturePanel
                    mode={mode}
                    onUseManual={() => setMode("MANUAL")}
                    onUploaded={(receipt) => {
                        void queryClient.invalidateQueries({ queryKey: RECEIPTS_KEY });
                        toast(
                            receipt.state === "QUEUED"
                                ? strings.receipt.uploadedAnalysing
                                : strings.receipt.uploaded,
                            "success",
                        );
                        router.replace(`/receipts/${receipt.id}`);
                    }}
                />
            ) : categories.isPending || me.isPending ? (
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
