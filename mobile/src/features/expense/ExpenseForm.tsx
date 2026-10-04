import { useEffect, useState } from "react";
import { Keyboard, StyleSheet, View } from "react-native";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { formatMoney } from "@/shared/money/money";
import { ApiError } from "@/shared/api/problem";
import { Button, Chip, MoneyInput, Selector, Text, TextInput } from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import { type Category, type Expense, suggestCategory } from "./expenseApi";
import {
    canChangeSharing,
    categoryLabel,
    type ExpenseFormValues,
    type FormErrors,
    initialValues,
    isSingleItem,
    selectableCategories,
    todayIn,
    toMoney,
    validateForm,
} from "./expenseRules";

const SUGGESTION_DEBOUNCE_MS = 400;

type Target = "amount" | "date" | "categoryId" | "banner";

/** Which field a server problem code belongs to; everything else is a banner. */
export function errorTarget(error: unknown): Target {
    if (!(error instanceof ApiError)) return "banner";
    switch (error.code) {
        case "INVALID_AMOUNT_FORMAT":
        case "AMOUNT_NOT_POSITIVE":
        case "AMOUNT_EXCEEDS_MAXIMUM":
            return "amount";
        case "EXPENSE_DATE_OUT_OF_RANGE":
        case "EXPENSE_REFUND_DATE_BEFORE_ORIGINAL":
            return "date";
        case "EXPENSE_CATEGORY_ARCHIVED":
        case "CATEGORY_NOT_FOUND":
            return "categoryId";
        default:
            return "banner";
    }
}

interface Props {
    /** Present when editing. */
    expense?: Expense | undefined;
    currency: string;
    timezone: string;
    categories: readonly Category[];
    me: string;
    submitLabel: string;
    submitting: boolean;
    /** Last server problem of the submit (mapped to a field or a banner here). */
    serverError?: unknown;
    onSubmit: (values: ExpenseFormValues) => void;
    now?: () => Date;
}

/** Quick-add / edit form: amount first (autofocused), then category chips, then Save. */
export function ExpenseForm({
    expense,
    currency,
    timezone,
    categories,
    me,
    submitLabel,
    submitting,
    serverError,
    onSubmit,
    now = () => new Date(),
}: Props) {
    const editing = expense !== undefined;
    const today = todayIn(timezone, now());
    const [values, setValues] = useState<ExpenseFormValues>(() =>
        expense
            ? initialValues(expense)
            : {
                  amount: "",
                  categoryId: null,
                  date: today,
                  merchant: "",
                  note: "",
                  // Approved quick-add defaults: SHARED, today (household timezone), payer = me.
                  sharingType: "SHARED",
              },
    );
    const [more, setMore] = useState(false);
    const [errors, setErrors] = useState<FormErrors>({});
    const [fetched, setFetched] = useState<{
        key: string;
        categoryId: string;
        source: string;
    } | null>(null);
    const showAll = editing || more;
    const singleItem = !editing || isSingleItem(expense);
    const pickable = selectableCategories(
        categories,
        expense ? expense.items.map((i) => i.categoryId) : [],
    );
    const sharingEditable = !editing || canChangeSharing(expense, me);

    const set = <K extends keyof ExpenseFormValues>(key: K, value: ExpenseFormValues[K]) =>
        setValues((v) => ({ ...v, [key]: value }));

    // Merchant -> category suggestion (BR-CAT-04): debounced, shown as a chip, applied only on tap.
    const merchant = values.merchant.trim();
    const merchantChanged = !editing || merchant !== (expense.merchant ?? "");
    const sharingType = values.sharingType;
    const suggestionKey = `${sharingType}:${merchant}`;
    useEffect(() => {
        if (merchant === "" || !merchantChanged || !singleItem) return;
        let cancelled = false;
        const timer = setTimeout(() => {
            suggestCategory(merchant, sharingType)
                .then((s) => {
                    if (!cancelled)
                        setFetched({
                            key: suggestionKey,
                            categoryId: s.categoryId,
                            source: s.source,
                        });
                })
                .catch(() => {
                    // A missing suggestion is never an error for the user.
                });
        }, SUGGESTION_DEBOUNCE_MS);
        return () => {
            cancelled = true;
            clearTimeout(timer);
        };
    }, [merchant, merchantChanged, sharingType, singleItem, suggestionKey]);

    // A suggestion for a previous merchant text is never shown.
    const suggestion = fetched?.key === suggestionKey ? fetched : null;
    const suggested = suggestion
        ? pickable.find((c) => c.id === suggestion.categoryId) // BR-CAT-06: only offered categories
        : undefined;

    const target = serverError ? errorTarget(serverError) : null;
    const serverText = serverError ? errorMessage(serverError) : undefined;
    const fieldError = (name: keyof FormErrors) =>
        errors[name] ?? (target === name ? serverText : undefined);

    const submit = () => {
        const found = validateForm(values, {
            currency,
            today,
            categoryRequired: singleItem,
            dateChanged: !editing || values.date !== expense.date,
        });
        setErrors(found);
        if (Object.keys(found).length === 0) onSubmit(values);
    };

    return (
        <View style={styles.form}>
            {singleItem ? (
                <MoneyInput
                    label={strings.expense.amount}
                    value={values.amount}
                    onChangeValue={(a) => set("amount", a)}
                    currency={currency}
                    autoFocus={!editing}
                    returnKeyType="done"
                    onSubmitEditing={() => {
                        // Category already chosen: save; otherwise reveal the chips.
                        if (values.categoryId) submit();
                        else Keyboard.dismiss();
                    }}
                    error={fieldError("amount")}
                    testID="expense-amount"
                />
            ) : (
                <ReadOnlyItems expense={expense} categories={categories} />
            )}
            {singleItem ? (
                <View style={styles.group}>
                    <Text variant="caption" tone="secondary" bold>
                        {strings.expense.category}
                    </Text>
                    <View
                        accessibilityRole="radiogroup"
                        accessibilityLabel={strings.category.pickerLabel}
                        style={styles.chips}
                    >
                        {pickable.map((c) => (
                            <Chip
                                key={c.id}
                                label={categoryLabel(c)}
                                selected={values.categoryId === c.id}
                                onPress={() => set("categoryId", c.id)}
                            />
                        ))}
                    </View>
                    {suggested && suggestion && values.categoryId !== suggested.id ? (
                        <Button
                            variant="secondary"
                            label={strings.category.suggested(
                                categoryLabel(suggested),
                                strings.category.suggestionSource[suggestion.source] ??
                                    suggestion.source,
                            )}
                            onPress={() => set("categoryId", suggested.id)}
                        />
                    ) : null}
                    {fieldError("categoryId") ? (
                        <Text variant="caption" tone="danger" accessibilityLiveRegion="polite">
                            {fieldError("categoryId")}
                        </Text>
                    ) : null}
                </View>
            ) : null}
            {showAll ? (
                <View style={styles.form}>
                    <TextInput
                        label={strings.expense.date}
                        value={values.date}
                        onChangeText={(d) => set("date", d.trim())}
                        autoCapitalize="none"
                        autoCorrect={false}
                        keyboardType="numbers-and-punctuation"
                        error={fieldError("date")}
                    />
                    <TextInput
                        label={strings.expense.merchant}
                        value={values.merchant}
                        onChangeText={(m) => set("merchant", m)}
                        maxLength={120}
                    />
                    <TextInput
                        label={strings.expense.note}
                        value={values.note}
                        onChangeText={(n) => set("note", n)}
                        multiline
                    />
                    {sharingEditable ? (
                        <View style={styles.group}>
                            <Text variant="caption" tone="secondary" bold>
                                {strings.expense.sharing}
                            </Text>
                            <Selector
                                label={strings.expense.sharing}
                                value={values.sharingType}
                                onChange={(s) => set("sharingType", s)}
                                options={[
                                    { value: "SHARED", label: strings.expense.shared },
                                    { value: "PERSONAL", label: strings.expense.personal },
                                ]}
                            />
                        </View>
                    ) : (
                        <Text variant="caption" tone="secondary">
                            {strings.expense.sharingChangeNotice}
                        </Text>
                    )}
                </View>
            ) : null}
            {target === "banner" && serverText ? (
                <Text tone="danger" bold accessibilityRole="alert" accessibilityLiveRegion="polite">
                    {serverText}
                </Text>
            ) : null}
            {editing ? null : (
                <Button
                    variant="secondary"
                    label={more ? strings.expense.lessOptions : strings.expense.moreOptions}
                    onPress={() => setMore((m) => !m)}
                />
            )}
            <Button
                label={submitLabel}
                loading={submitting}
                onPress={submit}
                testID="expense-submit"
            />
        </View>
    );
}

function ReadOnlyItems({
    expense,
    categories,
}: {
    expense: Expense | undefined;
    categories: readonly Category[];
}) {
    return (
        <View style={styles.group}>
            <Text variant="caption" tone="secondary">
                {strings.expense.itemsReadOnly}
            </Text>
            {expense?.items.map((item) => {
                const category = categories.find((c) => c.id === item.categoryId);
                return (
                    <Text key={item.id}>
                        {`${category ? categoryLabel(category) : strings.expense.untitled}: ${formatMoney(toMoney(item.amount))}`}
                    </Text>
                );
            })}
        </View>
    );
}

const styles = StyleSheet.create({
    form: { gap: spacing.lg },
    group: { gap: spacing.sm },
    chips: { flexDirection: "row", flexWrap: "wrap", gap: spacing.sm },
});
