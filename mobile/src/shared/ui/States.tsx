import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Button } from "./Button";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { radius, size, spacing } from "./theme/tokens";

export function Skeleton({ variant }: { variant: "row" | "card" | "number" }) {
    const colors = useColors();
    const shape = {
        row: styles.row,
        card: styles.card,
        number: styles.number,
    }[variant];
    return (
        <View
            accessible
            accessibilityRole="progressbar"
            accessibilityLabel={strings.loading}
            accessibilityState={{ busy: true }}
            style={[shape, { backgroundColor: colors.skeleton }]}
        />
    );
}

export function EmptyState({
    title,
    message,
    actionLabel,
    onAction,
}: {
    title: string;
    message: string;
    actionLabel?: string;
    onAction?: () => void;
}) {
    const colors = useColors();
    return (
        <View style={styles.state}>
            <View style={[styles.badge, { backgroundColor: colors.card }]}>
                <Icon name="empty" size="lg" color={colors.textSecondary} />
            </View>
            <Text variant="h3" accessibilityRole="header" style={styles.centered}>
                {title}
            </Text>
            <Text tone="secondary" style={styles.centered}>
                {message}
            </Text>
            {actionLabel && onAction ? <Button label={actionLabel} onPress={onAction} /> : null}
        </View>
    );
}

/** Human-readable failure with retry. Never pass raw codes or stack traces as `message`. */
export function ErrorState({
    message = strings.errorDefault,
    onRetry,
}: {
    message?: string;
    onRetry: () => void;
}) {
    const colors = useColors();
    return (
        <View style={styles.state} accessibilityLiveRegion="polite">
            <View style={[styles.badge, { backgroundColor: colors.card }]}>
                <Icon name="error" size="lg" color={colors.danger} />
            </View>
            <Text tone="danger" weight="semibold" style={styles.centered}>
                {message}
            </Text>
            <Button label={strings.retry} variant="secondary" onPress={onRetry} />
        </View>
    );
}

const styles = StyleSheet.create({
    state: { alignItems: "center", gap: spacing.md, padding: spacing.xl },
    centered: { textAlign: "center" },
    badge: {
        width: size.controlLg,
        height: size.controlLg,
        borderRadius: radius.pill,
        alignItems: "center",
        justifyContent: "center",
    },
    row: { height: spacing.xxl + spacing.xl + spacing.lg, borderRadius: radius.lg },
    card: { height: spacing.xxl * 4, borderRadius: radius.lg },
    number: { height: spacing.xl, width: spacing.xxl * 4, borderRadius: radius.sm },
});
