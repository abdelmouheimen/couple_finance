import { useState } from "react";
import { useForm } from "react-hook-form";
import { useHouseholdState } from "@/features/household/HouseholdProvider";
import { resendVerification, signOut, verifyEmail } from "@/features/identity/authApi";
import { pendingEmail } from "@/features/identity/pendingEmail";
import { type VerifyCodeValues, verifyCodeSchema } from "@/features/identity/schemas";
import { FormTextField } from "@/shared/forms/FormTextField";
import { zodResolver } from "@/shared/forms/zodResolver";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { Button, Screen, Text } from "@/shared/ui";

/** Shown when the backend answers 403 EMAIL_NOT_VERIFIED: verify with the emailed code, then continue. */
export default function VerifyEmail() {
    const { refetch } = useHouseholdState();
    const [error, setError] = useState<unknown>(null);
    const [info, setInfo] = useState<string | null>(null);
    const [resending, setResending] = useState(false);
    const { control, handleSubmit, formState } = useForm<VerifyCodeValues>({
        resolver: zodResolver(verifyCodeSchema),
        defaultValues: { token: "" },
    });
    const submit = handleSubmit(async ({ token }) => {
        setError(null);
        setInfo(null);
        try {
            await verifyEmail(token);
            refetch();
        } catch (e) {
            setError(e);
        }
    });
    const resend = async () => {
        const email = pendingEmail.get();
        setError(null);
        setResending(true);
        try {
            if (email) await resendVerification(email);
            setInfo(strings.auth.resent);
        } catch (e) {
            setError(e);
        } finally {
            setResending(false);
        }
    };
    return (
        <Screen>
            <Text variant="headline" accessibilityRole="header">
                {strings.auth.verifyTitle}
            </Text>
            <Text tone="secondary">{strings.auth.verifyMessage}</Text>
            <FormTextField
                control={control}
                name="token"
                label={strings.auth.verifyCode}
                autoCapitalize="none"
                autoCorrect={false}
                returnKeyType="done"
                onSubmitEditing={() => void submit()}
            />
            {error ? (
                <Text tone="danger" accessibilityLiveRegion="polite">
                    {errorMessage(error)}
                </Text>
            ) : null}
            {info ? <Text accessibilityLiveRegion="polite">{info}</Text> : null}
            <Button
                label={strings.auth.verifyAction}
                loading={formState.isSubmitting}
                onPress={() => void submit()}
            />
            <Button
                variant="secondary"
                label={strings.auth.verifyCheckAgain}
                onPress={() => refetch()}
            />
            <Button
                variant="secondary"
                label={strings.auth.resend}
                loading={resending}
                onPress={() => void resend()}
            />
            <Button
                variant="secondary"
                label={strings.auth.signOut}
                onPress={() => void signOut()}
            />
        </Screen>
    );
}
