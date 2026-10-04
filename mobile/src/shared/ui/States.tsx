import { StyleSheet, View } from "react-native";
import { strings } from "@/shared/i18n/strings";
import { Button } from "./Button";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { radius, spacing } from "./theme/tokens";

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
    return (
        <View style={styles.state}>
            <Icon name="empty" size="lg" />
            <Text variant="title" accessibilityRole="header">
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
    return (
        <View style={styles.state} accessibilityLiveRegion="polite">
            <Icon name="error" size="lg" />
            <Text tone="danger" bold style={styles.centered}>
                {message}
            </Text>
            <Button label={strings.retry} variant="secondary" onPress={onRetry} />
        </View>
    );
}

const styles = StyleSheet.create({
    state: { alignItems: "center", gap: spacing.md, padding: spacing.xl },
    centered: { textAlign: "center" },
    row: { height: spacing.xxl + spacing.lg, borderRadius: radius.md },
    card: { height: spacing.xxl * 4, borderRadius: radius.lg },
    number: { height: spacing.xl, width: spacing.xxl * 4, borderRadius: radius.sm },
});
