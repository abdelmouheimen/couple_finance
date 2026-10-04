import { type ReactNode } from "react";
import {
    KeyboardAvoidingView,
    Platform,
    type RefreshControlProps,
    ScrollView,
    StyleSheet,
    View,
} from "react-native";
import { StatusBar } from "expo-status-bar";
import { type Edge, SafeAreaView } from "react-native-safe-area-context";
import { strings } from "@/shared/i18n/strings";
import { IconButton } from "./IconButton";
import { useColors } from "./theme/theme";
import { spacing } from "./theme/tokens";

interface Props {
    children: ReactNode;
    /** Safe-area edges to respect. Include "bottom" on screens without a tab bar (modals). */
    edges?: readonly Edge[];
    /** Set to false when the child owns scrolling (e.g. a FlatList): avoids nested virtualized lists. */
    scroll?: boolean;
    /** Pull-to-refresh support. */
    refreshControl?: React.ReactElement<RefreshControlProps>;
    /** Extra bottom content inset, e.g. `fabContentInset` on tab screens with a floating button. */
    bottomInset?: number;
    /** When set, a visible close control is rendered (modal screens). */
    onClose?: () => void;
}

const DEFAULT_EDGES: readonly Edge[] = ["top", "left", "right"];

/**
 * Standard screen wrapper: safe areas, status bar and keyboard avoidance so a focused input is never
 * obscured. Exactly one keyboard mechanism is used (KeyboardAvoidingView) to avoid double compensation.
 */
export function Screen({
    children,
    edges = DEFAULT_EDGES,
    scroll = true,
    refreshControl,
    bottomInset = 0,
    onClose,
}: Props) {
    const colors = useColors();
    const body = scroll ? (
        <ScrollView
            contentContainerStyle={[styles.content, { paddingBottom: spacing.lg + bottomInset }]}
            keyboardShouldPersistTaps="handled"
            keyboardDismissMode="on-drag"
            refreshControl={refreshControl}
        >
            {children}
        </ScrollView>
    ) : (
        <View style={[styles.content, styles.flex, { paddingBottom: spacing.lg + bottomInset }]}>
            {children}
        </View>
    );
    return (
        <SafeAreaView
            edges={edges as Edge[]}
            style={[styles.flex, { backgroundColor: colors.background }]}
        >
            <StatusBar style={colors.scheme === "dark" ? "light" : "dark"} />
            {onClose ? (
                <View style={styles.close}>
                    <IconButton icon="close" label={strings.close} onPress={onClose} />
                </View>
            ) : null}
            <KeyboardAvoidingView
                style={styles.flex}
                behavior={Platform.OS === "ios" ? "padding" : "height"}
            >
                {body}
            </KeyboardAvoidingView>
        </SafeAreaView>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    content: { padding: spacing.lg, gap: spacing.lg, flexGrow: 1 },
    close: { alignItems: "flex-end", paddingHorizontal: spacing.lg, paddingTop: spacing.sm },
});
