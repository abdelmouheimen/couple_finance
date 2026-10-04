import { type Control, type FieldPath, type FieldValues } from "react-hook-form";
import { useState } from "react";
import { Pressable, View } from "react-native";
import { FormTextField } from "@/shared/forms/FormTextField";
import { strings } from "@/shared/i18n/strings";
import { Text } from "@/shared/ui";
import { minTouchTarget } from "@/shared/ui/theme/tokens";

interface Props<T extends FieldValues> {
    control: Control<T>;
    name: FieldPath<T>;
    label: string;
    isNew?: boolean;
    serverError?: string | undefined;
    onSubmitEditing?: () => void;
}

/** Password input with a labelled visibility toggle and the right autofill hints. */
export function PasswordField<T extends FieldValues>({
    control,
    name,
    label,
    isNew,
    serverError,
    onSubmitEditing,
}: Props<T>) {
    const [visible, setVisible] = useState(false);
    const toggleLabel = visible ? strings.auth.hidePassword : strings.auth.showPassword;
    return (
        <View>
            <FormTextField
                control={control}
                name={name}
                label={label}
                serverError={serverError}
                secureTextEntry={!visible}
                autoCapitalize="none"
                autoCorrect={false}
                autoComplete={isNew ? "new-password" : "current-password"}
                textContentType={isNew ? "newPassword" : "password"}
                returnKeyType="done"
                onSubmitEditing={onSubmitEditing}
            />
            <Pressable
                accessibilityRole="button"
                accessibilityLabel={toggleLabel}
                onPress={() => setVisible((v) => !v)}
                style={{ minHeight: minTouchTarget, justifyContent: "center" }}
            >
                <Text tone="primary" bold>
                    {toggleLabel}
                </Text>
            </Pressable>
        </View>
    );
}
