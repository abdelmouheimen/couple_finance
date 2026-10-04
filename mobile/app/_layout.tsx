import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { Stack } from "expo-router";
import { useState } from "react";
import { ToastProvider } from "@/shared/ui";

export default function RootLayout() {
    const [queryClient] = useState(() => new QueryClient());
    return (
        <QueryClientProvider client={queryClient}>
            <ToastProvider>
                <Stack screenOptions={{ headerShown: false }}>
                    <Stack.Screen name="(tabs)" />
                    <Stack.Screen name="add-expense" options={{ presentation: "modal" }} />
                </Stack>
            </ToastProvider>
        </QueryClientProvider>
    );
}
