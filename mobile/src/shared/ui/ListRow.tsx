import { type ReactNode } from "react";
import { Pressable, StyleSheet, View } from "react-native";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { useColors } from "./theme/theme";
import { minTouchTarget, spacing } from "./theme/tokens";

interface Props {
    title: string;
    subtitle?: string;
    trailing?: ReactNode;
    /** Small decorative element before the text (e.g. a legend swatch). */
    leading?: ReactNode;
    onPress?: () => void;
    testID?: string;
    accessibilityHint?: string;
}

export function ListRow({
    title,
    subtitle,
    trailing,
    leading,
    onPress,
    testID,
    accessibilityHint,
}: Props) {
    const colors = useColors();
    const content = (
        <>
            {leading}
            <View style={styles.main}>
                <Text bold>{title}</Text>
                {subtitle ? (
                    <Text variant="caption" tone="secondary">
                        {subtitle}
                    </Text>
                ) : null}
            </View>
            {trailing}
            {onPress ? <Icon name="chevron" color={colors.textSecondary} /> : null}
        </>
    );
    if (!onPress) {
        return (
            <View testID={testID} accessible style={styles.row}>
                {content}
            </View>
        );
    }
    return (
        <Pressable
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={subtitle ? `${title}, ${subtitle}` : title}
            accessibilityHint={accessibilityHint}
            onPress={onPress}
            style={styles.row}
        >
            {content}
        </Pressable>
    );
}

const styles = StyleSheet.create({
    row: {
        minHeight: minTouchTarget,
        flexDirection: "row",
        alignItems: "center",
        gap: spacing.md,
        paddingVertical: spacing.sm,
    },
    main: { flex: 1, gap: spacing.xs },
});
