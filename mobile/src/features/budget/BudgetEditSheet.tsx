import { useState } from "react";
import { StyleSheet, View } from "react-native";
import { errorMessage, isVersionConflict } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { Button, IconButton, MoneyInput, Selector, Sheet, Text } from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import type { Budget, Category } from "./budgetApi";
import {
    type BudgetForm,
    categoryName,
    type FormErrors,
    formFromBudget,
    hasErrors,
    validateForm,
} from "./budgetForm";

interface Props {
    visible: boolean;
    budget: Budget | null;
    categories: readonly Category[];
    currency: string;
    past: boolean;
    saving: boolean;
    /** Server error of the last save attempt. */
    error: unknown;
    onSubmit: (form: BudgetForm) => void;
    onReload: () => void;
    onClose: () => void;
}

function EditForm({
    budget,
    categories,
    currency,
    past,
    saving,
    error,
    onSubmit,
    onReload,
}: Props) {
    const [form, setForm] = useState<BudgetForm>(() => formFromBudget(budget));
    const [errors, setErrors] = useState<FormErrors>({ lines: {} });
    const byId = new Map(categories.map((c) => [c.id, c]));
    const used = new Set(form.lines.map((l) => l.categoryId));
    const available = categories.filter((c) => !c.archived && !used.has(c.id));

    const submit = () => {
        const found = validateForm(form);
        setErrors(found);
        if (!hasErrors(found)) onSubmit(form);
    };

    return (
        <>
            {past ? <Text tone="secondary">{strings.budget.pastNote}</Text> : null}
            <MoneyInput
                label={strings.budget.overallField}
                value={form.overall}
                currency={currency}
                onChangeValue={(overall) => setForm((f) => ({ ...f, overall }))}
                {...(errors.overall ? { error: errors.overall } : {})}
            />
            {form.lines.map((line) => {
                const name = categoryName(byId.get(line.categoryId));
                const lineError = errors.lines[line.categoryId];
                return (
                    <View key={line.categoryId} style={styles.line}>
                        <View style={styles.flex}>
                            <MoneyInput
                                label={strings.budget.categoryField(name)}
                                value={line.amount}
                                currency={currency}
                                onChangeValue={(amount) =>
                                    setForm((f) => ({
                                        ...f,
                                        lines: f.lines.map((l) =>
                                            l.categoryId === line.categoryId ? { ...l, amount } : l,
                                        ),
                                    }))
                                }
                                {...(lineError ? { error: lineError } : {})}
                            />
                        </View>
                        <IconButton
                            icon="close"
                            label={strings.budget.removeCategory(name)}
                            onPress={() =>
                                setForm((f) => ({
                                    ...f,
                                    lines: f.lines.filter((l) => l.categoryId !== line.categoryId),
                                }))
                            }
                        />
                    </View>
                );
            })}
            {available.length > 0 ? (
                <Selector
                    label={strings.budget.addCategory}
                    value={null}
                    options={available.map((c) => ({ value: c.id, label: categoryName(c) }))}
                    onChange={(id) =>
                        setForm((f) => ({
                            ...f,
                            lines: [...f.lines, { categoryId: id, amount: "" }],
                        }))
                    }
                />
            ) : (
                <Text tone="secondary">{strings.budget.noMoreCategories}</Text>
            )}
            {errors.form ? (
                <Text tone="danger" accessibilityLiveRegion="polite">
                    {errors.form}
                </Text>
            ) : null}
            {error ? (
                <Text tone="danger" accessibilityLiveRegion="polite">
                    {errorMessage(error)}
                </Text>
            ) : null}
            {isVersionConflict(error) ? (
                <Button label={strings.budget.reload} variant="secondary" onPress={onReload} />
            ) : null}
            <Button label={strings.budget.save} loading={saving} onPress={submit} />
        </>
    );
}

/** Edit sheet: the form is mounted only while open, so it always starts from the latest budget. */
export function BudgetEditSheet(props: Props) {
    return (
        <Sheet visible={props.visible} title={strings.budget.editTitle} onClose={props.onClose}>
            {props.visible ? <EditForm {...props} /> : null}
        </Sheet>
    );
}

const styles = StyleSheet.create({
    line: { flexDirection: "row", gap: spacing.sm, alignItems: "flex-end" },
    flex: { flex: 1 },
});
