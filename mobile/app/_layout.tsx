import { QueryClientProvider } from "@tanstack/react-query";
import { Stack } from "expo-router";
import { useEffect } from "react";
import { signOut } from "@/features/identity/authApi";
import { HouseholdProvider, useHouseholdState } from "@/features/household/HouseholdProvider";
import { queryClient, session } from "@/shared/api/instance";
import { useSessionStatus } from "@/shared/auth/useSession";
import { errorMessage } from "@/shared/i18n/errorMessage";
import { strings } from "@/shared/i18n/strings";
import { Button, ErrorState, Screen, Skeleton, ToastProvider } from "@/shared/ui";
import { PrivacyShield } from "@/shared/ui/PrivacyShield";
import { useColors } from "@/shared/ui/theme/theme";

function Splash() {
    return (
        <Screen scroll={false}>
            <Skeleton variant="card" />
        </Screen>
    );
}

function Failure({ message, onRetry }: { message: string; onRetry: () => void }) {
    return (
        <Screen>
            <ErrorState message={message} onRetry={onRetry} />
            <Button
                variant="secondary"
                label={strings.auth.signOut}
                onPress={() => void signOut()}
            />
        </Screen>
    );
}

/** Auth + household gate: route groups are reachable only in the state they belong to. */
function Navigator() {
    const status = useSessionStatus(session);
    const { state, refetch } = useHouseholdState();
    const signedIn = status === "signedIn";

    if (status === "restoring") return <Splash />;
    if (status === "restoreFailed") {
        return (
            <Failure message={strings.auth.restoreFailed} onRetry={() => void session.restore()} />
        );
    }
    if (signedIn && state.kind === "loading") return <Splash />;
    if (signedIn && state.kind === "error") {
        return <Failure message={errorMessage(state.error)} onRetry={refetch} />;
    }
    return (
        <Stack screenOptions={{ headerShown: false }}>
            <Stack.Protected guard={!signedIn}>
                <Stack.Screen name="(auth)" />
            </Stack.Protected>
            <Stack.Protected guard={signedIn && state.kind === "emailNotVerified"}>
                <Stack.Screen name="(verify)" />
            </Stack.Protected>
            <Stack.Protected guard={signedIn && state.kind === "none"}>
                <Stack.Screen name="(onboarding)" />
            </Stack.Protected>
            <Stack.Protected guard={signedIn && state.kind === "ready"}>
                <Stack.Screen name="(tabs)" />
                <Stack.Screen name="add-expense" options={{ presentation: "modal" }} />
                <Stack.Screen name="expense/[id]" />
                <Stack.Screen name="expense/edit/[id]" />
                <Stack.Screen name="categories" />
            </Stack.Protected>
        </Stack>
    );
}

function Root() {
    const status = useSessionStatus(session);
    useColors(); // theme resolves from the OS scheme
    useEffect(() => {
        void session.restore();
    }, []);
    return (
        <HouseholdProvider enabled={status === "signedIn"}>
            <Navigator />
        </HouseholdProvider>
    );
}

export default function RootLayout() {
    return (
        <QueryClientProvider client={queryClient}>
            <ToastProvider>
                <Root />
                <PrivacyShield />
            </ToastProvider>
        </QueryClientProvider>
    );
}
