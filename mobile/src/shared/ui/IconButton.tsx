import { Pressable, StyleSheet } from "react-native";
import { Icon } from "./Icon";
import { useColors } from "./theme/theme";
import { type IconName, minTouchTarget, radius } from "./theme/tokens";

interface Props {
    icon: IconName;
    /** Required: an icon-only control must have an accessible name. */
    label: string;
    onPress: () => void;
    testID?: string;
}

export function IconButton({ icon, label, onPress, testID }: Props) {
    const colors = useColors();
    return (
        <Pressable
            testID={testID}
            accessibilityRole="button"
            accessibilityLabel={label}
            onPress={onPress}
            style={[styles.base, { backgroundColor: colors.surfaceMuted }]}
        >
            <Icon name={icon} />
        </Pressable>
    );
}

const styles = StyleSheet.create({
    base: {
        minHeight: minTouchTarget,
        minWidth: minTouchTarget,
        borderRadius: radius.pill,
        alignItems: "center",
        justifyContent: "center",
    },
});
