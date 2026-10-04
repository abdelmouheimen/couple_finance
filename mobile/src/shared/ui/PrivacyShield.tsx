import { useEffect, useState } from "react";
import { AppState, type AppStateStatus, StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { spacing } from "./theme/tokens";

/**
 * Privacy screen (security.md §9): while the app is not active (app switcher snapshot, incoming
 * call overlay) an opaque cover replaces the content so no financial data is captured in the OS
 * snapshot. Renders nothing while the app is active.
 * Known limit: Android may snapshot before the cover renders (no FLAG_SECURE; needs a native module).
 */
export function PrivacyShield() {
    const colors = useColors();
    const [state, setState] = useState<AppStateStatus>(AppState.currentState);
    useEffect(() => {
        const subscription = AppState.addEventListener("change", setState);
        return () => subscription.remove();
    }, []);
    if (state === "active") return null;
    return (
        <View
            testID="privacy-shield"
            accessibilityViewIsModal
            importantForAccessibility="no-hide-descendants"
            style={[styles.cover, { backgroundColor: colors.background }]}
        >
            <Text bold>{strings.appName}</Text>
        </View>
    );
}

const styles = StyleSheet.create({
    cover: {
        position: "absolute",
        top: 0,
        left: 0,
        right: 0,
        bottom: 0,
        zIndex: 1000,
        alignItems: "center",
        justifyContent: "center",
        gap: spacing.md,
    },
});
