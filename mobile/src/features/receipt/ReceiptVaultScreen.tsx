import { useInfiniteQuery } from "@tanstack/react-query";
import { useRouter } from "expo-router";
import { useMemo, useState } from "react";
import { ActivityIndicator, FlatList, Pressable, StyleSheet, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { useIsReadOnly } from "@/features/household/HouseholdProvider";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import {
    EmptyState,
    ErrorState,
    Icon,
    IconButton,
    IconTile,
    Segmented,
    Skeleton,
    Text,
    useColors,
} from "@/shared/ui";
import { categoryColors, minTouchTarget, radius, size, spacing } from "@/shared/ui/theme/tokens";
import { listReceipts, type Receipt, RECEIPTS_KEY } from "./receiptApi";
import {
    filterReceipts,
    type KindFilter,
    receiptDate,
    receiptStateLabel,
    receiptSummary,
} from "./receiptFormat";

const FILTERS: readonly { value: KindFilter; label: string }[] = [
    { value: "ALL", label: strings.receipt.filterAll },
    { value: "IMAGES", label: strings.receipt.filterPhotos },
    { value: "PDF", label: strings.receipt.filterPdf },
];

function ReceiptRow({ receipt, onPress }: { receipt: Receipt; onPress: () => void }) {
    const colors = useColors();
    const date = receiptDate(receipt.createdAt);
    const state = receiptStateLabel(receipt);
    const summary = receiptSummary(receipt);
    return (
        <Pressable
            testID={`receipt-row-${receipt.id}`}
            accessibilityRole="button"
            accessibilityLabel={strings.receipt.rowLabel(
                date,
                strings.receipt.kind[receipt.sourceKind],
                strings.receipt.pages(receipt.pages.length),
                state,
            )}
            onPress={onPress}
            style={[styles.row, { backgroundColor: colors.card }]}
        >
            <IconTile icon="receipt" color={categoryColors.other} />
            <View style={styles.main}>
                <Text weight="medium">{date}</Text>
                <Text variant="caption" tone="secondary">
                    {summary}
                </Text>
            </View>
            <Text variant="caption" tone="secondary">
                {state}
            </Text>
            <Icon name="chevron" color={colors.textSecondary} />
        </Pressable>
    );
}

/** The receipt vault: the caller's receipts, newest first (BR-RCP-16: drafts are private to their uploader). */
export function ReceiptVaultScreen() {
    const router = useRouter();
    const colors = useColors();
    const readOnly = useIsReadOnly();
    const [kind, setKind] = useState<KindFilter>("ALL");
    const query = useInfiniteQuery({
        queryKey: [...RECEIPTS_KEY, "list"],
        queryFn: ({ pageParam }) => listReceipts(pageParam),
        initialPageParam: undefined as string | undefined,
        getNextPageParam: (last) => last.nextCursor ?? undefined,
        retry: false,
    });
    const all = useMemo(() => query.data?.pages.flatMap((p) => p.items) ?? [], [query.data]);
    const rows = filterReceipts(all, kind);

    const header = (
        <View style={styles.header}>
            <View style={styles.titleRow}>
                <IconButton
                    variant="plain"
                    icon="back"
                    label={strings.back}
                    onPress={() => router.back()}
                />
                <Text variant="h1" accessibilityRole="header" style={styles.flex}>
                    {strings.receipt.vaultTitle}
                </Text>
                {readOnly ? null : (
                    <IconButton
                        icon="add"
                        label={strings.receipt.vaultAdd}
                        onPress={() => router.push("/add-expense")}
                        testID="vault-add"
                    />
                )}
            </View>
            <Segmented
                label={strings.receipt.filterLabel}
                options={FILTERS}
                value={kind}
                onChange={setKind}
            />
        </View>
    );

    let empty: React.ReactElement;
    if (query.isPending) {
        empty = (
            <View style={styles.list}>
                <Skeleton variant="row" />
                <Skeleton variant="row" />
            </View>
        );
    } else if (query.isError) {
        empty = (
            <ErrorState message={errorMessage(query.error)} onRetry={() => void query.refetch()} />
        );
    } else {
        empty = (
            <EmptyState
                title={strings.receipt.vaultEmptyTitle}
                message={strings.receipt.vaultEmptyMessage}
                {...(readOnly
                    ? {}
                    : {
                          actionLabel: strings.receipt.vaultAdd,
                          onAction: () => router.push("/add-expense"),
                      })}
            />
        );
    }

    return (
        <SafeAreaView
            edges={["top", "left", "right", "bottom"]}
            style={[styles.flex, { backgroundColor: colors.background }]}
        >
            <FlatList
                testID="receipt-vault"
                data={rows}
                keyExtractor={(r) => r.id}
                contentContainerStyle={styles.content}
                ListHeaderComponent={header}
                ListEmptyComponent={empty}
                refreshing={query.isRefetching && !query.isFetchingNextPage}
                onRefresh={() => void query.refetch()}
                onEndReachedThreshold={0.5}
                onEndReached={() => {
                    if (query.hasNextPage && !query.isFetchingNextPage) void query.fetchNextPage();
                }}
                ListFooterComponent={
                    query.isFetchingNextPage ? (
                        <ActivityIndicator accessibilityLabel={strings.loading} />
                    ) : null
                }
                renderItem={({ item }) => (
                    <ReceiptRow
                        receipt={item}
                        onPress={() => router.push(`/receipts/${item.id}`)}
                    />
                )}
            />
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    content: {
        paddingHorizontal: spacing.screen,
        paddingTop: spacing.sm,
        paddingBottom: spacing.xl,
        gap: spacing.sm,
        flexGrow: 1,
    },
    header: { gap: spacing.md, marginBottom: spacing.sm },
    titleRow: { flexDirection: "row", alignItems: "center", gap: spacing.sm },
    list: { gap: spacing.sm },
    row: {
        minHeight: Math.max(size.iconTile + spacing.xl, minTouchTarget),
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.md,
        padding: spacing.md,
        borderRadius: radius.lg,
    },
    main: { flex: 1, gap: spacing.xs },
});
