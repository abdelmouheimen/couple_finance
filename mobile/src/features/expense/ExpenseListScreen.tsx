import { useLocalSearchParams, useRouter } from "expo-router";
import { useEffect, useMemo, useState } from "react";
import { ActivityIndicator, FlatList, ScrollView, StyleSheet, View } from "react-native";
import { SafeAreaView } from "react-native-safe-area-context";
import { periodTitle } from "@/features/budget/periodLabel";
import { useHouseholdContext } from "@/features/household/HouseholdProvider";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import {
    Chip,
    EmptyState,
    ErrorState,
    FilterChip,
    IconButton,
    ScreenTitle,
    Segmented,
    Selector,
    Sheet,
    Skeleton,
    Text,
    TextInput,
} from "@/shared/ui";
import { useColors } from "@/shared/ui/theme/theme";
import { spacing } from "@/shared/ui/theme/tokens";
import { categoryVisual } from "./categoryVisual";
import type { Expense, ExpenseFilters, ExpenseScope } from "./expenseApi";
import { categoryLabel, shiftDate } from "./expenseRules";
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
    const params = useLocalSearchParams<{
        categoryId?: string;
        periodStart?: string;
        scope?: string;
        nav?: string;
    }>();
    const [scope, setScope] = useState<ExpenseScope>(
        params.scope === "PERSONAL" ? "PERSONAL" : "HOUSEHOLD",
    );
    const [period, setPeriod] = useState<Period>(
        params.periodStart && params.periodStart !== currentPeriod.start ? "all" : "current",
    );
    const [from, setFrom] = useState("");
    const [to, setTo] = useState("");
    const [search, setSearch] = useState("");
    const [categoryId, setCategoryId] = useState<string | undefined>(params.categoryId);
    const [filtersOpen, setFiltersOpen] = useState(false);
    const q = useDebounced(search.trim(), SEARCH_DEBOUNCE_MS);

    // The tab stays mounted: re-seed the filters whenever a drill-down pushes new params
    // (adjusting state during render, keyed on the params, instead of an effect).
    const paramsKey = `${params.categoryId ?? ""}|${params.periodStart ?? ""}|${params.scope ?? ""}|${params.nav ?? ""}`;
    const [seenParams, setSeenParams] = useState(paramsKey);
    if (seenParams !== paramsKey) {
        setSeenParams(paramsKey);
        if (paramsKey !== "|||") {
            setCategoryId(params.categoryId);
            setScope(params.scope === "PERSONAL" ? "PERSONAL" : "HOUSEHOLD");
            setPeriod(
                params.periodStart && params.periodStart !== currentPeriod.start
                    ? "all"
                    : "current",
            );
        }
    }

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
    const categoryById = useMemo(
        () => new Map((categories.data ?? []).map((c) => [c.id, c])),
        [categories.data],
    );
    const nameOf = (id: string | undefined) => {
        const category = categoryById.get(id ?? "");
        return category ? categoryLabel(category) : undefined;
    };

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

    const activeCategory =
        categoryId === undefined
            ? undefined
            : (nameOf(categoryId) ?? strings.dashboard.unknownCategory);
    const activeCount = categoryId === undefined ? 0 : 1;
    const periodChip =
        period === "current"
            ? periodTitle(currentPeriod)
            : period === "all"
              ? strings.expense.periodAll
              : strings.expense.periodCustom;

    const header = (
        <View style={styles.header}>
            <ScreenTitle>{strings.expense.title}</ScreenTitle>
            <View style={styles.searchRow}>
                <View style={styles.flex}>
                    <TextInput
                        hideLabel
                        icon="search"
                        label={strings.expense.searchLabel}
                        placeholder={strings.expense.searchPlaceholder}
                        value={search}
                        onChangeText={setSearch}
                        autoCapitalize="none"
                        autoCorrect={false}
                        returnKeyType="search"
                        maxLength={100}
                    />
                </View>
                <IconButton
                    icon="filters"
                    label={
                        activeCount > 0
                            ? strings.expense.filtersActive(activeCount)
                            : strings.expense.filters
                    }
                    onPress={() => setFiltersOpen(true)}
                    testID="open-filters"
                />
            </View>
            <Segmented
                label={strings.expense.scopeLabel}
                value={scope}
                onChange={setScope}
                options={[
                    { value: "HOUSEHOLD", label: strings.scope.HOUSEHOLD },
                    { value: "PERSONAL", label: strings.scope.PERSONAL },
                ]}
            />
            <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.chipRow}
                style={styles.chipScroll}
            >
                <FilterChip
                    testID="period-chip"
                    icon="calendar"
                    label={periodChip}
                    accessibilityLabel={`${strings.expense.periodLabel}, ${periodChip}`}
                    onPress={() => setFiltersOpen(true)}
                />
                <FilterChip
                    testID="category-chip"
                    icon="categories"
                    label={strings.expense.categories}
                    count={activeCount}
                    accessibilityLabel={`${strings.expense.categoryFilter}, ${
                        activeCategory ?? strings.expense.allCategories
                    }`}
                    onPress={() => setFiltersOpen(true)}
                />
                {activeCategory !== undefined ? (
                    <FilterChip
                        testID="active-category-filter"
                        label={activeCategory}
                        removable
                        accessibilityLabel={strings.expense.removeFilter(activeCategory)}
                        onPress={() => setCategoryId(undefined)}
                    />
                ) : null}
            </ScrollView>
        </View>
    );

    const filtersSheet = (
        <Sheet
            visible={filtersOpen}
            title={strings.expense.filtersTitle}
            onClose={() => setFiltersOpen(false)}
        >
            <Text variant="caption" tone="secondary">
                {strings.expense.periodLabel}
            </Text>
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
                <View style={styles.dates}>
                    <View style={styles.flex}>
                        <TextInput
                            label={strings.expense.dateFrom}
                            value={from}
                            onChangeText={setFrom}
                            placeholder="AAAA-MM-JJ"
                            error={
                                from !== "" && !DATE_RE.test(from)
                                    ? strings.expense.dateFormatError
                                    : undefined
                            }
                            autoCapitalize="none"
                            keyboardType="numbers-and-punctuation"
                        />
                    </View>
                    <View style={styles.flex}>
                        <TextInput
                            label={strings.expense.dateTo}
                            value={to}
                            onChangeText={setTo}
                            placeholder="AAAA-MM-JJ"
                            error={
                                to !== "" && !DATE_RE.test(to)
                                    ? strings.expense.dateFormatError
                                    : undefined
                            }
                            autoCapitalize="none"
                            keyboardType="numbers-and-punctuation"
                        />
                    </View>
                </View>
            ) : null}
            <Text variant="caption" tone="secondary">
                {strings.expense.categories}
            </Text>
            <View
                accessibilityRole="radiogroup"
                accessibilityLabel={strings.expense.categoryFilter}
                style={styles.chips}
            >
                <Chip
                    label={strings.expense.allCategories}
                    selected={categoryId === undefined}
                    onPress={() => {
                        setCategoryId(undefined);
                        setFiltersOpen(false);
                    }}
                />
                {(categories.data ?? []).map((c) => (
                    <Chip
                        key={c.id}
                        label={categoryLabel(c)}
                        selected={categoryId === c.id}
                        onPress={() => {
                            setCategoryId(c.id);
                            setFiltersOpen(false);
                        }}
                    />
                ))}
            </View>
        </Sheet>
    );

    let empty: React.ReactElement | null = null;
    if (query.isPending) {
        empty = (
            <View style={styles.loading}>
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
                renderItem={({ item }) =>
                    item.type === "header" ? (
                        <Text
                            variant="caption"
                            tone="secondary"
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
                                nameOf(item.expense.items[0]?.categoryId) ??
                                strings.expense.untitled
                            }
                            dateLabel={formatDate(item.expense.date)}
                            categoryLabel={nameOf(item.expense.items[0]?.categoryId)}
                            visual={categoryVisual(
                                categoryById.get(item.expense.items[0]?.categoryId ?? ""),
                            )}
                            onPress={() => router.push(`/expense/${item.expense.id}`)}
                        />
                    )
                }
            />
            {filtersSheet}
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    content: {
        paddingHorizontal: spacing.screen,
        paddingTop: spacing.lg,
        paddingBottom: spacing.xl,
        gap: spacing.sm,
        flexGrow: 1,
    },
    header: { gap: spacing.md, marginBottom: spacing.sm },
    loading: { gap: spacing.sm },
    searchRow: { flexDirection: "row", alignItems: "center", gap: spacing.md },
    chipScroll: { marginHorizontal: -spacing.screen },
    chipRow: { flexDirection: "row", gap: spacing.sm, paddingHorizontal: spacing.screen },
    dates: { flexDirection: "row", gap: spacing.sm },
    chips: { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
    date: { marginTop: spacing.md, marginBottom: spacing.xs },
});
