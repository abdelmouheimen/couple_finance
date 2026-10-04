import { Link } from "expo-router";
import { signIn } from "@/features/identity/authApi";
import { pendingEmail } from "@/features/identity/pendingEmail";
import { SignInForm } from "@/features/identity/SignInForm";
import { strings } from "@/shared/i18n/strings";
import { Screen, Text } from "@/shared/ui";

export default function SignIn() {
    return (
        <Screen>
            <Text variant="headline" accessibilityRole="header">
                {strings.auth.signInTitle}
            </Text>
            <SignInForm
                onSubmit={async (values) => {
                    pendingEmail.set(values.email);
                    await signIn(values);
                }}
            />
            <Link href="/sign-up" accessibilityRole="link">
                <Text tone="primary">{strings.auth.toSignUp}</Text>
            </Link>
        </Screen>
    );
}
