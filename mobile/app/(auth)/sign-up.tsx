import { Link } from "expo-router";
import { useState } from "react";
import { signUp } from "@/features/identity/authApi";
import { pendingEmail } from "@/features/identity/pendingEmail";
import { SignUpForm } from "@/features/identity/SignUpForm";
import { strings } from "@/shared/i18n/strings";
import { EmptyState, Screen, Text } from "@/shared/ui";

export default function SignUp() {
    const [done, setDone] = useState(false);
    return (
        <Screen>
            {done ? (
                <EmptyState
                    title={strings.auth.checkEmailTitle}
                    message={strings.auth.checkEmailMessage}
                />
            ) : (
                <>
                    <Text variant="headline" accessibilityRole="header">
                        {strings.auth.signUpTitle}
                    </Text>
                    <SignUpForm
                        onSubmit={async (values) => {
                            await signUp(values);
                            pendingEmail.set(values.email);
                            setDone(true);
                        }}
                    />
                </>
            )}
            <Link href="/sign-in" accessibilityRole="link">
                <Text tone="primary">{strings.auth.toSignIn}</Text>
            </Link>
        </Screen>
    );
}
