import { useRouter } from "expo-router";
import { useState } from "react";
import { StyleSheet, View } from "react-native";
import { useIsReadOnly } from "@/features/household/HouseholdProvider";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import {
    Button,
    Card,
    ErrorState,
    ListRow,
    Screen,
    Sheet,
    Skeleton,
    Text,
    TextInput,
    useToast,
} from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import { type Category, createCategory, updateCategory } from "./expenseApi";
import { useCategories, useInvalidateCategories } from "./expenseQueries";
import { categoryLabel } from "./expenseRules";

const MAX_NAME = 40;

function validName(name: string): boolean {
    const trimmed = name.trim();
    return trimmed.length >= 1 && trimmed.length <= MAX_NAME;
}

/** Category management: add, rename and archive custom categories; system ones are read-only (BR-CAT-03). */
export function CategoriesScreen() {
    const router = useRouter();
    const toast = useToast();
    const readOnly = useIsReadOnly();
    const categories = useCategories(true);
    const invalidate = useInvalidateCategories();
    const [name, setName] = useState("");
    const [nameError, setNameError] = useState<string | undefined>(undefined);
    const [banner, setBanner] = useState<string | undefined>(undefined);
    const [busy, setBusy] = useState(false);
    const [renaming, setRenaming] = useState<Category | null>(null);
    const [renameValue, setRenameValue] = useState("");
    const [renameError, setRenameError] = useState<string | undefined>(undefined);

    const run = async (
        action: () => Promise<unknown>,
        done: string,
        onError: (m: string) => void,
    ): Promise<boolean> => {
        if (busy) return false;
        setBusy(true);
        try {
            await action();
            await invalidate();
            toast(done);
            return true;
        } catch (e) {
            onError(errorMessage(e));
            return false;
        } finally {
            setBusy(false);
        }
    };

    const add = async () => {
        if (!validName(name)) {
            setNameError(strings.category.nameRequired);
            return;
        }
        setNameError(undefined);
        const ok = await run(
            () => createCategory(name.trim()),
            strings.category.added,
            setNameError,
        );
        if (ok) setName("");
    };

    const rename = async () => {
        if (!renaming) return;
        if (!validName(renameValue)) {
            setRenameError(strings.category.nameRequired);
            return;
        }
        const target = renaming;
        const ok = await run(
            () => updateCategory(target.id, target.version, { name: renameValue.trim() }),
            strings.category.updated,
            setRenameError,
        );
        if (ok) setRenaming(null);
    };

    const toggleArchive = (c: Category) =>
        void run(
            () => updateCategory(c.id, c.version, { archived: !c.archived }),
            strings.category.updated,
            setBanner,
        );

    return (
        <Screen onClose={() => router.back()}>
            <Text variant="headline" accessibilityRole="header">
                {strings.category.manageTitle}
            </Text>
            {readOnly ? null : (
                <Card>
                    <TextInput
                        label={strings.category.newName}
                        value={name}
                        onChangeText={setName}
                        maxLength={MAX_NAME}
                        error={nameError}
                        returnKeyType="done"
                        onSubmitEditing={() => void add()}
                    />
                    <Button
                        label={strings.category.add}
                        loading={busy}
                        onPress={() => void add()}
                    />
                </Card>
            )}
            {banner ? (
                <Text tone="danger" bold accessibilityRole="alert">
                    {banner}
                </Text>
            ) : null}
            {categories.isPending ? (
                <Skeleton variant="card" />
            ) : categories.isError ? (
                <ErrorState
                    message={errorMessage(categories.error)}
                    onRetry={() => void categories.refetch()}
                />
            ) : (
                <Card>
                    {categories.data.map((c) => (
                        <View key={c.id} style={styles.item}>
                            <ListRow
                                title={categoryLabel(c)}
                                subtitle={
                                    c.type === "SYSTEM"
                                        ? strings.category.systemBadge
                                        : c.archived
                                          ? strings.category.archivedBadge
                                          : undefined
                                }
                            />
                            {c.type === "CUSTOM" && !readOnly ? (
                                <View style={styles.actions}>
                                    <Button
                                        variant="secondary"
                                        label={`${strings.category.rename} ${categoryLabel(c)}`}
                                        onPress={() => {
                                            setRenameValue(c.name ?? "");
                                            setRenameError(undefined);
                                            setRenaming(c);
                                        }}
                                    />
                                    <Button
                                        variant="secondary"
                                        label={`${c.archived ? strings.category.unarchive : strings.category.archive} ${categoryLabel(c)}`}
                                        onPress={() => toggleArchive(c)}
                                    />
                                </View>
                            ) : null}
                        </View>
                    ))}
                </Card>
            )}
            <Sheet
                visible={renaming !== null}
                title={strings.category.renameTitle}
                onClose={() => setRenaming(null)}
            >
                <TextInput
                    label={strings.category.newName}
                    value={renameValue}
                    onChangeText={setRenameValue}
                    maxLength={MAX_NAME}
                    error={renameError}
                />
                <Button
                    label={strings.category.save}
                    loading={busy}
                    onPress={() => void rename()}
                />
            </Sheet>
        </Screen>
    );
}

const styles = StyleSheet.create({
    item: { gap: spacing.sm },
    actions: { gap: spacing.sm },
});
