import {
    createContext,
    type ReactNode,
    useCallback,
    useContext,
    useEffect,
    useMemo,
    useRef,
    useState,
} from "react";
import { AccessibilityInfo, StyleSheet, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { borderWidth, elevation, radius, spacing } from "./theme/tokens";

export type ToastKind = "success" | "error";
interface ToastState {
    message: string;
    kind: ToastKind;
}

const ToastContext = createContext<(message: string, kind?: ToastKind) => void>(() => {});

export const TOAST_DURATION_MS = 4000;

export function useToast() {
    return useContext(ToastContext);
}

/** Non-blocking feedback, announced to assistive tech; auto-dismisses. */
export function ToastProvider({ children }: { children: ReactNode }) {
    const colors = useColors();
    const insets = useSafeAreaInsets();
    const [toast, setToast] = useState<ToastState | null>(null);
    const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

    const show = useCallback((message: string, kind: ToastKind = "success") => {
        if (timer.current) clearTimeout(timer.current);
        setToast({ message, kind });
        AccessibilityInfo.announceForAccessibility(message);
        timer.current = setTimeout(() => setToast(null), TOAST_DURATION_MS);
    }, []);

    useEffect(
        () => () => {
            if (timer.current) clearTimeout(timer.current);
        },
        [],
    );

    const value = useMemo(() => show, [show]);
    const tone = toast?.kind === "error" ? colors.danger : colors.success;

    return (
        <ToastContext.Provider value={value}>
            {children}
            {toast ? (
                <View
                    pointerEvents="none"
                    style={[styles.host, { top: insets.top + spacing.sm }]}
                    accessibilityLiveRegion="polite"
                >
                    <View
                        accessible
                        accessibilityRole="alert"
                        style={[
                            styles.toast,
                            elevation.floating,
                            { backgroundColor: colors.surface, borderColor: tone },
                        ]}
                    >
                        <Icon name={toast.kind === "error" ? "error" : "check"} color={tone} />
                        <Text style={styles.message}>{toast.message}</Text>
                    </View>
                </View>
            ) : null}
        </ToastContext.Provider>
    );
}

const styles = StyleSheet.create({
    host: { position: "absolute", left: spacing.lg, right: spacing.lg },
    toast: {
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.md,
        borderRadius: radius.md,
        borderWidth: borderWidth.thick,
        padding: spacing.lg,
    },
    message: { flex: 1 },
});
