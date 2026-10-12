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
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { minTouchTarget, size, spacing } from "./theme/tokens";

interface Props {
    children: ReactNode;
    /** Safe-area edges to respect. Include "bottom" on screens without a tab bar (modals). */
    edges?: readonly Edge[];
    /** Set to false when the child owns scrolling (e.g. a FlatList): avoids nested virtualized lists. */
    scroll?: boolean;
    /** Pull-to-refresh support. */
    refreshControl?: React.ReactElement<RefreshControlProps>;
    /** Extra bottom content inset. */
    bottomInset?: number;
    /** Tighter vertical rhythm for information-dense screens (Home). */
    compact?: boolean;
    /** Title of a pushed/modal screen, centred in the header row next to the close/back control. */
    title?: string;
    /** When set, a visible close control is rendered (modal screens). */
    onClose?: () => void;
    /** When set, a visible back control is rendered (pushed screens). */
    onBack?: () => void;
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
    compact = false,
    title,
    onClose,
    onBack,
}: Props) {
    const colors = useColors();
    const dismiss = onBack ?? onClose;
    const body = scroll ? (
        <ScrollView
            contentContainerStyle={[
                styles.content,
                compact && styles.compact,
                { paddingBottom: spacing.xl + bottomInset },
            ]}
            keyboardShouldPersistTaps="handled"
            keyboardDismissMode="on-drag"
            refreshControl={refreshControl}
        >
            {children}
        </ScrollView>
    ) : (
        <View
            style={[
                styles.content,
                styles.flex,
                compact && styles.compact,
                { paddingBottom: spacing.xl + bottomInset },
            ]}
        >
            {children}
        </View>
    );
    return (
        <SafeAreaView
            edges={edges as Edge[]}
            style={[styles.flex, { backgroundColor: colors.background }]}
        >
            <StatusBar style="light" />
            {dismiss ? (
                <View style={styles.header}>
                    <IconButton
                        variant="plain"
                        icon={onBack ? "back" : "close"}
                        label={onBack ? strings.back : strings.close}
                        onPress={dismiss}
                    />
                    {title ? (
                        <Text
                            variant="h3"
                            accessibilityRole="header"
                            numberOfLines={1}
                            style={styles.title}
                        >
                            {title}
                        </Text>
                    ) : (
                        <View style={styles.flex} />
                    )}
                    <View style={styles.balance} />
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

/** Large title at the top of a tab screen (H1 of the approved type scale). */
export function ScreenTitle({ children }: { children: string }) {
    return (
        <Text variant="h1" accessibilityRole="header">
            {children}
        </Text>
    );
}

/** Title of a section inside a screen (H3), optionally with a trailing action. */
export function SectionTitle({ children, action }: { children: string; action?: ReactNode }) {
    return (
        <View style={styles.section}>
            <Text variant="h3" accessibilityRole="header" style={styles.flex}>
                {children}
            </Text>
            {action}
        </View>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    content: {
        paddingHorizontal: spacing.screen,
        paddingTop: spacing.lg,
        gap: spacing.md,
        flexGrow: 1,
    },
    compact: { gap: spacing.sm },
    header: {
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.sm,
        paddingHorizontal: spacing.sm,
        paddingTop: spacing.sm,
    },
    title: { flex: 1, textAlign: "center" },
    balance: { width: Math.max(size.controlMd, minTouchTarget) },
    section: { flexDirection: "row", alignItems: "center", gap: spacing.md },
});
