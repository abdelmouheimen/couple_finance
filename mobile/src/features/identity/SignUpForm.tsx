import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@/shared/forms/zodResolver";
import { FormTextField } from "@/shared/forms/FormTextField";
import { errorMessage, fieldErrors } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { Button, Text } from "@/shared/ui";
import { PasswordField } from "./PasswordField";
import { type SignUpValues, signUpSchema } from "./schemas";

interface Props {
    onSubmit: (values: SignUpValues) => Promise<void>;
}

export function SignUpForm({ onSubmit }: Props) {
    const { control, handleSubmit, formState } = useForm<SignUpValues>({
        resolver: zodResolver(signUpSchema),
        defaultValues: { displayName: "", email: "", password: "" },
    });
    const [error, setError] = useState<unknown>(null);
    const submit = handleSubmit(async (values) => {
        setError(null);
        try {
            await onSubmit(values);
        } catch (e) {
            setError(e);
        }
    });
    const server = fieldErrors(error);
    return (
        <>
            <FormTextField
                control={control}
                name="displayName"
                label={strings.auth.displayName}
                serverError={server.displayName}
                autoComplete="name"
                textContentType="name"
                returnKeyType="next"
            />
            <FormTextField
                control={control}
                name="email"
                label={strings.auth.email}
                serverError={server.email}
                autoCapitalize="none"
                autoCorrect={false}
                autoComplete="email"
                keyboardType="email-address"
                textContentType="username"
                returnKeyType="next"
            />
            <PasswordField
                control={control}
                name="password"
                label={strings.auth.passwordNew}
                serverError={server.password}
                isNew
                onSubmitEditing={() => void submit()}
            />
            {error ? (
                <Text tone="danger" accessibilityLiveRegion="polite">
                    {errorMessage(error)}
                </Text>
            ) : null}
            <Button
                label={strings.auth.signUpAction}
                loading={formState.isSubmitting}
                onPress={() => void submit()}
            />
        </>
    );
}
