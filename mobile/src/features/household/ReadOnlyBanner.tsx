import { StyleSheet, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { strings } from "@/shared/i18n/strings";
import { Text, useColors } from "@/shared/ui";
import { spacing } from "@/shared/ui/theme/tokens";
import { useIsReadOnly } from "./HouseholdProvider";

/** BR-HH-10: shown whenever the household is DISSOLVED (read-only). Renders nothing otherwise. */
export function ReadOnlyBanner() {
    const readOnly = useIsReadOnly();
    const colors = useColors();
    const insets = useSafeAreaInsets();
    if (!readOnly) return null;
    return (
        <View
            accessible
            accessibilityRole="alert"
            style={[
                styles.banner,
                { backgroundColor: colors.surfaceMuted, paddingTop: insets.top + spacing.sm },
            ]}
        >
            <Text bold>{strings.household.dissolvedBanner}</Text>
        </View>
    );
}

const styles = StyleSheet.create({
    banner: { paddingHorizontal: spacing.lg, paddingBottom: spacing.sm },
});
