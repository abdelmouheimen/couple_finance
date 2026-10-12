import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { useState } from "react";
import { Image, StyleSheet, View } from "react-native";
import { useIsReadOnly } from "@/features/household/HouseholdProvider";
import { ApiError } from "@/shared/api/problem";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import {
    Button,
    Card,
    ConfirmSheet,
    EmptyState,
    ErrorState,
    IconButton,
    Screen,
    Skeleton,
    Text,
    useColors,
    useToast,
} from "@/shared/ui";
import { radius, spacing, viewfinderAspectRatio } from "@/shared/ui/theme/tokens";
import {
    deleteReceipt,
    getReceipt,
    getReceiptPageUrl,
    isAnalysisPending,
    isOpenReceipt,
    type Receipt,
    RECEIPTS_KEY,
    requestReceiptAnalysis,
} from "./receiptApi";
import { sharePage } from "./receiptDevice";
import { receiptDate, receiptStateLabel, receiptSummary } from "./receiptFormat";

/** Poll interval while an analysis is queued or running. */
const ANALYSIS_POLL_MS = 3000;

/** What the screen says about the analysis of an open receipt, if anything. */
function analysisMessage(receipt: Receipt): string | null {
    switch (receipt.state) {
        case "QUEUED":
        case "ANALYSING":
            return strings.receipt.analysisPending;
        case "READY_FOR_REVIEW":
            return strings.receipt.analysisReady;
        case "UPLOADED":
            return strings.receipt.analysisNeedsConsent;
        case "CONFIRMED":
        case "ATTACHED":
            return strings.receipt.linkedNotice;
        case "FAILED":
            return `${
                strings.receipt.failure[receipt.analysisFailureReason ?? ""] ??
                strings.receipt.failureDefault
            } ${strings.receipt.failureManual}`;
        default:
            return null;
    }
}

/**
 * One receipt of the vault: page preview through a short-lived URL (fetched on demand, kept in memory only, never
 * cached across screens), download/share and deletion with confirmation.
 */
export function ReceiptDetailScreen({ id }: { id: string }) {
    const router = useRouter();
    const toast = useToast();
    const colors = useColors();
    const readOnly = useIsReadOnly();
    const queryClient = useQueryClient();
    const [position, setPosition] = useState(1);
    const [confirming, setConfirming] = useState(false);
    const [actionError, setActionError] = useState<string | null>(null);
    const receipt = useQuery({
        queryKey: [...RECEIPTS_KEY, "detail", id],
        queryFn: () => getReceipt(id),
        retry: false,
        refetchInterval: (query) =>
            query.state.data && isAnalysisPending(query.state.data) ? ANALYSIS_POLL_MS : false,
    });
    const page = useQuery({
        queryKey: [...RECEIPTS_KEY, "page-url", id, position],
        queryFn: () => getReceiptPageUrl(id, position),
        enabled: receipt.isSuccess,
        retry: false,
        // The URL expires after 5 minutes: never reuse it from the cache.
        gcTime: 0,
        staleTime: 0,
    });
    const back = () => router.back();

    if (receipt.isPending) {
        return (
            <Screen
                edges={["top", "left", "right", "bottom"]}
                title={strings.receipt.detailTitle}
                onBack={back}
            >
                <Skeleton variant="card" />
            </Screen>
        );
    }
    if (receipt.isError) {
        const notFound = receipt.error instanceof ApiError && receipt.error.status === 404;
        return (
            <Screen
                edges={["top", "left", "right", "bottom"]}
                title={strings.receipt.detailTitle}
                onBack={back}
            >
                {notFound ? (
                    <EmptyState
                        title={strings.errors.receiptNotFound}
                        message={strings.expense.notFoundMessage}
                    />
                ) : (
                    <ErrorState
                        message={errorMessage(receipt.error)}
                        onRetry={() => void receipt.refetch()}
                    />
                )}
            </Screen>
        );
    }

    const data = receipt.data;
    const total = data.pages.length;

    const share = async () => {
        setActionError(null);
        try {
            const fresh = await getReceiptPageUrl(id, position);
            await sharePage(fresh.url, id, position);
        } catch (e) {
            setActionError(errorMessage(e));
        }
    };

    const retriesLeft = data.manualRetriesLeft ?? 0;
    const canAnalyse =
        !readOnly && (data.state === "UPLOADED" || (data.state === "FAILED" && retriesLeft > 0));
    const message = analysisMessage(data);

    const analyse = async () => {
        setActionError(null);
        try {
            const queued = await requestReceiptAnalysis(id);
            queryClient.setQueryData([...RECEIPTS_KEY, "detail", id], queued);
            toast(strings.receipt.analysisQueued, "success");
        } catch (e) {
            setActionError(errorMessage(e));
        }
    };

    const remove = async () => {
        setConfirming(false);
        setActionError(null);
        try {
            await deleteReceipt(id);
            await queryClient.invalidateQueries({ queryKey: RECEIPTS_KEY });
            toast(strings.receipt.deleted, "success");
            router.back();
        } catch (e) {
            setActionError(errorMessage(e));
        }
    };

    return (
        <Screen
            edges={["top", "left", "right", "bottom"]}
            title={strings.receipt.detailTitle}
            onBack={back}
        >
            <Card>
                <Text weight="semibold">{receiptDate(data.createdAt)}</Text>
                <Text variant="caption" tone="secondary">
                    {`${receiptSummary(data)} · ${receiptStateLabel(data)}`}
                </Text>
                {message ? (
                    <Text
                        variant="caption"
                        tone={data.state === "FAILED" ? "danger" : "secondary"}
                        testID="receipt-analysis-message"
                        accessibilityLiveRegion="polite"
                    >
                        {message}
                    </Text>
                ) : null}
                {data.state === "FAILED" ? (
                    <Text variant="caption" tone="secondary">
                        {strings.receipt.retriesLeft(retriesLeft)}
                    </Text>
                ) : null}
            </Card>
            <View style={[styles.preview, { backgroundColor: colors.card }]}>
                {page.data ? (
                    <Image
                        testID="receipt-page-image"
                        accessible
                        accessibilityLabel={strings.receipt.pageLabel(position, total)}
                        source={{ uri: page.data.url }}
                        resizeMode="contain"
                        style={styles.image}
                    />
                ) : page.isError ? (
                    <ErrorState
                        message={strings.receipt.previewUnavailable}
                        onRetry={() => void page.refetch()}
                    />
                ) : (
                    <Skeleton variant="card" />
                )}
            </View>
            {total > 1 ? (
                <View style={styles.pager}>
                    <IconButton
                        icon="chevronBack"
                        label={strings.receipt.previousPage}
                        onPress={() => setPosition((p) => p - 1)}
                        disabled={position <= 1}
                    />
                    <Text style={styles.flex} weight="medium">
                        {strings.receipt.pageLabel(position, total)}
                    </Text>
                    <IconButton
                        icon="chevron"
                        label={strings.receipt.nextPage}
                        onPress={() => setPosition((p) => p + 1)}
                        disabled={position >= total}
                    />
                </View>
            ) : null}
            {actionError ? (
                <Text tone="danger" accessibilityLiveRegion="polite" testID="receipt-action-error">
                    {actionError}
                </Text>
            ) : null}
            {!readOnly && isOpenReceipt(data) ? (
                <Button
                    size="lg"
                    label={strings.receipt.reviewAction}
                    onPress={() => router.push(`/receipts/review/${id}`)}
                />
            ) : null}
            {data.expenseId ? (
                <Button
                    label={strings.receipt.viewExpense}
                    onPress={() => router.push(`/expense/${data.expenseId}`)}
                />
            ) : null}
            {canAnalyse ? (
                <Button
                    variant="outline"
                    label={
                        data.state === "FAILED"
                            ? strings.receipt.retryAnalysis
                            : strings.receipt.analyse
                    }
                    onPress={analyse}
                />
            ) : null}
            <Button
                variant="secondary"
                icon="upload"
                label={strings.receipt.share}
                onPress={share}
            />
            {readOnly || !isOpenReceipt(data) ? null : (
                <Button
                    variant="destructive"
                    label={strings.receipt.delete}
                    onPress={() => setConfirming(true)}
                />
            )}
            <ConfirmSheet
                visible={confirming}
                title={strings.receipt.deleteTitle}
                message={strings.receipt.deleteMessage}
                confirmLabel={strings.receipt.delete}
                onConfirm={() => void remove()}
                onCancel={() => setConfirming(false)}
            />
        </Screen>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1, textAlign: "center" },
    preview: {
        aspectRatio: viewfinderAspectRatio,
        borderRadius: radius.lg,
        overflow: "hidden",
        justifyContent: "center",
    },
    image: { flex: 1 },
    pager: { flexDirection: "row", alignItems: "center", gap: spacing.md },
});
