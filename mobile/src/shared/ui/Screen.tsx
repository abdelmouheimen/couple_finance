import { type ReactNode } from "react";
import { KeyboardAvoidingView, Platform, ScrollView, StyleSheet } from "react-native";
import { StatusBar } from "expo-status-bar";
import { SafeAreaView } from "react-native-safe-area-context";
import { useColors } from "./theme/theme";
import { spacing } from "./theme/tokens";

/**
 * Standard screen wrapper: safe areas, status bar, and keyboard avoidance so a focused input is
 * never obscured (scrolls, keeps taps working while the keyboard is open).
 */
export function Screen({ children }: { children: ReactNode }) {
    const colors = useColors();
    return (
        <SafeAreaView
            edges={["top", "left", "right"]}
            style={[styles.flex, { backgroundColor: colors.background }]}
        >
            <StatusBar style={colors.scheme === "dark" ? "light" : "dark"} />
            <KeyboardAvoidingView
                style={styles.flex}
                behavior={Platform.OS === "ios" ? "padding" : "height"}
            >
                <ScrollView
                    contentContainerStyle={styles.content}
                    keyboardShouldPersistTaps="handled"
                    keyboardDismissMode="on-drag"
                    automaticallyAdjustKeyboardInsets
                >
                    {children}
                </ScrollView>
            </KeyboardAvoidingView>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.lg, flexGrow: 1 },
});
