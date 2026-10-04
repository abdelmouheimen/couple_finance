import { type ReactNode, useEffect, useRef } from "react";
import {
    AccessibilityInfo,
    findNodeHandle,
    KeyboardAvoidingView,
    Modal,
    Platform,
    Pressable,
    ScrollView,
    StyleSheet,
    useWindowDimensions,
    View,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { strings } from "@/shared/i18n/strings";
import { Button } from "./Button";
import { IconButton } from "./IconButton";
import { Text } from "./Text";
import { useColors, useReducedMotion } from "./theme/theme";
import { elevation, radius, sheetMaxHeightRatio, spacing } from "./theme/tokens";

interface SheetProps {
    visible: boolean;
    title: string;
    onClose: () => void;
    children?: ReactNode;
}

/**
 * Bottom sheet modal. Dismissible (backdrop, hardware back, visible Close button inside the modal
 * view so VoiceOver can reach it); focus moves to the title on open. On close, the native Modal
 * returns accessibility focus to the previously focused element. Content scrolls (200% font) and is
 * keyboard-aware. Toasts are hidden behind a Modal: show errors inline inside sheets.
 */
export function Sheet({ visible, title, onClose, children }: SheetProps) {
    const colors = useColors();
    const reduced = useReducedMotion();
    const titleRef = useRef<View>(null);
    const insets = useSafeAreaInsets();
    const { height } = useWindowDimensions();

    useEffect(() => {
        if (!visible) return;
        const handle = titleRef.current ? findNodeHandle(titleRef.current) : null;
        if (handle) AccessibilityInfo.setAccessibilityFocus(handle);
    }, [visible]);

    return (
        <Modal
            visible={visible}
            transparent
            animationType={reduced ? "none" : "slide"}
            onRequestClose={onClose}
        >
            <KeyboardAvoidingView
                style={[styles.overlay, { backgroundColor: colors.overlay }]}
                behavior={Platform.OS === "ios" ? "padding" : undefined}
            >
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={strings.close}
                    style={styles.backdrop}
                    onPress={onClose}
                />
                <View
                    accessibilityViewIsModal
                    style={[
                        styles.sheet,
                        elevation.floating,
                        {
                            backgroundColor: colors.surface,
                            maxHeight: height * sheetMaxHeightRatio,
                            paddingBottom: spacing.xl + insets.bottom,
                        },
                    ]}
                >
                    <View style={styles.header}>
                        <View
                            ref={titleRef}
                            accessible
                            accessibilityRole="header"
                            style={styles.title}
                        >
                            <Text variant="title">{title}</Text>
                        </View>
                        <IconButton icon="close" label={strings.close} onPress={onClose} />
                    </View>
                    <ScrollView
                        contentContainerStyle={styles.body}
                        keyboardShouldPersistTaps="handled"
                    >
                        {children}
                    </ScrollView>
                </View>
            </KeyboardAvoidingView>
        </Modal>
    );
}

interface ConfirmProps {
    visible: boolean;
    title: string;
    message: string;
    confirmLabel: string;
    onConfirm: () => void;
    onCancel: () => void;
}

/** Destructive confirmation: nothing happens until the explicit second action (the confirm button). */
export function ConfirmSheet({
    visible,
    title,
    message,
    confirmLabel,
    onConfirm,
    onCancel,
}: ConfirmProps) {
    return (
        <Sheet visible={visible} title={title} onClose={onCancel}>
            <Text tone="secondary">{message}</Text>
            <View style={styles.actions}>
                <Button label={confirmLabel} variant="destructive" onPress={onConfirm} />
                <Button label={strings.cancel} variant="secondary" onPress={onCancel} />
            </View>
        </Sheet>
    );
}

const styles = StyleSheet.create({
    overlay: { flex: 1, justifyContent: "flex-end" },
    backdrop: { flex: 1 },
    sheet: {
        borderTopLeftRadius: radius.lg,
        borderTopRightRadius: radius.lg,
        paddingHorizontal: spacing.xl,
        paddingTop: spacing.xl,
        gap: spacing.lg,
    },
    header: { flexDirection: "row", alignItems: "center", gap: spacing.md },
    title: { flex: 1 },
    body: { gap: spacing.lg },
    actions: { gap: spacing.sm },
});
