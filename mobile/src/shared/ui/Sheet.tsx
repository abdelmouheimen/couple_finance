import { type ReactNode, useEffect, useRef } from "react";
import {
    AccessibilityInfo,
    findNodeHandle,
    Modal,
    Pressable,
    StyleSheet,
    View,
} from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Button } from "./Button";
import { Text } from "./Text";
import { useColors, useReducedMotion } from "./theme/theme";
import { elevation, radius, spacing } from "./theme/tokens";

interface SheetProps {
    visible: boolean;
    title: string;
    onClose: () => void;
    children?: ReactNode;
}

/** Bottom sheet modal. Dismissible (backdrop, hardware back, Close); focus moves to the title on open. */
export function Sheet({ visible, title, onClose, children }: SheetProps) {
    const colors = useColors();
    const reduced = useReducedMotion();
    const titleRef = useRef<View>(null);

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
            <View style={[styles.overlay, { backgroundColor: colors.overlay }]}>
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={strings.close}
                    style={styles.backdrop}
                    onPress={onClose}
                />
                <View
                    accessibilityViewIsModal
                    style={[styles.sheet, elevation.floating, { backgroundColor: colors.surface }]}
                >
                    <View ref={titleRef} accessible accessibilityRole="header">
                        <Text variant="title">{title}</Text>
                    </View>
                    {children}
                </View>
            </View>
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
        padding: spacing.xl,
        gap: spacing.lg,
    },
    actions: { gap: spacing.sm },
});
