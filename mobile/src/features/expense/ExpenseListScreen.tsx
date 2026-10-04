import { useRouter } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, FlatList, StyleSheet, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import {
    Card,
    Chip,
    EmptyState,
    ErrorState,
    MoneyText,
    Selector,
    Skeleton,
    Text,
    TextInput,
} from "@/shared/ui";
import { useColors } from "@/shared/ui/theme/theme";
import { fabContentInset, spacing } from "@/shared/ui/theme/tokens";
import type { Expense, ExpenseFilters, ExpenseScope } from "./expenseApi";
import { categoryLabel, shiftDate, toMoney } from "./expenseRules";
import { ExpenseRow } from "./ExpenseRow";
import { useCategories, useExpensePages } from "./expenseQueries";

type Period = "current" | "all" | "custom";
type Row = { type: "header"; date: string } | { type: "expense"; expense: Expense };

const DATE_RE = /^\d{4}-\d{2}-\d{2}$/;
const SEARCH_DEBOUNCE_MS = 400;

export function formatDate(date: string): string {
    try {
        return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeZone: "UTC" }).format(
            new Date(`${date}T00:00:00Z`),
        );
    } catch {
        return date;
    }
}

function useDebounced<T>(value: T, ms: number): T {
    const [debounced, setDebounced] = useState(value);
    useEffect(() => {
        const timer = setTimeout(() => setDebounced(value), ms);
        return () => clearTimeout(timer);
    }, [value, ms]);
    return debounced;
}

/** Expenses tab: cursor-paginated list grouped by date, scope switch, search and filters. */
export function ExpenseListScreen() {
    const router = useRouter();
    const colors = useColors();
    const { currentPeriod, readOnly } = useHouseholdContext();
    const [scope, setScope] = useState<ExpenseScope>("HOUSEHOLD");
    const [period, setPeriod] = useState<Period>("current");
    const [from, setFrom] = useState("");
    const [to, setTo] = useState("");
    const [search, setSearch] = useState("");
    const [categoryId, setCategoryId] = useState<string | undefined>(undefined);
    const q = useDebounced(search.trim(), SEARCH_DEBOUNCE_MS);

    const filters = useMemo<ExpenseFilters>(() => {
        let dateFrom: string | undefined;
        let dateTo: string | undefined;
        if (period === "current") {
            dateFrom = currentPeriod.start;
            dateTo = shiftDate(currentPeriod.end, -1, 0); // the API range is inclusive
        } else if (period === "custom") {
            dateFrom = DATE_RE.test(from) ? from : undefined;
            dateTo = DATE_RE.test(to) ? to : undefined;
        }
        return { scope, dateFrom, dateTo, categoryId, q: q === "" ? undefined : q };
    }, [scope, period, from, to, categoryId, q, currentPeriod.start, currentPeriod.end]);

    const query = useExpensePages(filters);
    const categories = useCategories(true);
    const categoryName = useMemo(
        () => new Map((categories.data ?? []).map((c) => [c.id, categoryLabel(c)])),
        [categories.data],
    );

    const filtered = period !== "current" || categoryId !== undefined || q !== "";
    const clearFilters = () => {
        setPeriod("current");
        setFrom("");
        setTo("");
        setSearch("");
        setCategoryId(undefined);
    };

    const rows = useMemo<Row[]>(() => {
        const out: Row[] = [];
        let last = "";
        for (const page of query.data?.pages ?? []) {
            for (const expense of page.items) {
                if (expense.date !== last) {
                    out.push({ type: "header", date: expense.date });
                    last = expense.date;
                }
                out.push({ type: "expense", expense });
            }
        }
        return out;
    }, [query.data]);

    const totals = query.data?.pages[0]?.totals;

    const header = (
        <View style={styles.header}>
            <Text variant="headline" accessibilityRole="header">
                {strings.expense.title}
            </Text>
            {totals ? (
                <Card>
                    <Text variant="caption" tone="secondary" bold>
                        {strings.expense.total}
                    </Text>
                    {/* Period total comes from the API (ExpenseTotals); never summed on the device. */}
                    <MoneyText money={toMoney(totals.net)} scope={totals.scope} />
                </Card>
            ) : null}
            <Selector
                label={strings.expense.scopeLabel}
                value={scope}
                onChange={setScope}
                options={[
                    { value: "HOUSEHOLD", label: strings.expense.shared },
                    { value: "PERSONAL", label: strings.expense.personal },
                ]}
            />
            <TextInput
                label={strings.expense.searchLabel}
                placeholder={strings.expense.searchPlaceholder}
                value={search}
                onChangeText={setSearch}
                autoCapitalize="none"
                autoCorrect={false}
                returnKeyType="search"
                maxLength={100}
            />
            <Selector
                label={strings.expense.periodLabel}
                value={period}
                onChange={setPeriod}
                options={[
                    { value: "current", label: strings.expense.periodCurrent },
                    { value: "all", label: strings.expense.periodAll },
                    { value: "custom", label: strings.expense.periodCustom },
                ]}
            />
            {period === "custom" ? (
                <View style={styles.header}>
                    <TextInput
                        label={strings.expense.dateFrom}
                        value={from}
                        onChangeText={setFrom}
                        placeholder="YYYY-MM-DD"
                        error={
                            from !== "" && !DATE_RE.test(from)
                                ? strings.expense.dateFormatError
                                : undefined
                        }
                        autoCapitalize="none"
                        keyboardType="numbers-and-punctuation"
                    />
                    <TextInput
                        label={strings.expense.dateTo}
                        value={to}
                        onChangeText={setTo}
                        placeholder="YYYY-MM-DD"
                        error={
                            to !== "" && !DATE_RE.test(to)
                                ? strings.expense.dateFormatError
                                : undefined
                        }
                        autoCapitalize="none"
                        keyboardType="numbers-and-punctuation"
                    />
                </View>
            ) : null}
            <View
                accessibilityRole="radiogroup"
                accessibilityLabel={strings.expense.categoryFilter}
                style={styles.chips}
            >
                <Chip
                    label={strings.expense.allCategories}
                    selected={categoryId === undefined}
                    onPress={() => setCategoryId(undefined)}
                />
                {(categories.data ?? []).map((c) => (
                    <Chip
                        key={c.id}
                        label={categoryLabel(c)}
                        selected={categoryId === c.id}
                        onPress={() => setCategoryId(c.id)}
                    />
                ))}
            </View>
        </View>
    );

    let empty: React.ReactElement | null = null;
    if (query.isPending) {
        empty = (
            <View style={styles.header}>
                <Skeleton variant="row" />
                <Skeleton variant="row" />
                <Skeleton variant="row" />
            </View>
        );
    } else if (query.isError) {
        empty = (
            <ErrorState message={errorMessage(query.error)} onRetry={() => void query.refetch()} />
        );
    } else if (filtered) {
        empty = (
            <EmptyState
                title={strings.expense.filteredEmptyTitle}
                message={strings.expense.filteredEmptyMessage}
                actionLabel={strings.expense.clearFilters}
                onAction={clearFilters}
            />
        );
    } else {
        empty = (
            <EmptyState
                title={strings.expense.emptyTitle}
                message={strings.expense.emptyMessage}
                {...(readOnly
                    ? {}
                    : {
                          actionLabel: strings.expense.emptyAction,
                          onAction: () => router.push("/add-expense"),
                      })}
            />
        );
    }

    return (
        <SafeAreaView
            edges={["top", "left", "right"]}
            style={[styles.flex, { backgroundColor: colors.background }]}
        >
            <FlatList<Row>
                testID="expense-list"
                data={rows}
                keyExtractor={(r) => (r.type === "header" ? `h-${r.date}` : r.expense.id)}
                keyboardShouldPersistTaps="handled"
                contentContainerStyle={[styles.content, { paddingBottom: fabContentInset }]}
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
                renderItem={({ item }) =>
                    item.type === "header" ? (
                        <Text
                            variant="caption"
                            tone="secondary"
                            bold
                            accessibilityRole="header"
                            style={styles.date}
                        >
                            {formatDate(item.date)}
                        </Text>
                    ) : (
                        <ExpenseRow
                            expense={item.expense}
                            title={
                                item.expense.merchant ??
                                categoryName.get(item.expense.items[0]?.categoryId ?? "") ??
                                strings.expense.untitled
                            }
                            dateLabel={formatDate(item.expense.date)}
                            categoryLabel={categoryName.get(
                                item.expense.items[0]?.categoryId ?? "",
                            )}
                            onPress={() => router.push(`/expense/${item.expense.id}`)}
                        />
                    )
                }
            />
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.sm, flexGrow: 1 },
    header: { gap: spacing.lg, marginBottom: spacing.md },
    chips: { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
    date: { marginTop: spacing.md },
});
