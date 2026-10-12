import { Pressable, StyleSheet, View } from "react-native";
import { Icon } from "./Icon";
import { Text } from "./Text";
import { type IconName } from "./theme/icons";
import { useColors } from "./theme/theme";
import {
    borderWidth,
    elevation,
    minTouchTarget,
    opacity,
    radius,
    size,
    spacing,
} from "./theme/tokens";

export interface NavItem {
    key: string;
    label: string;
    icon: IconName;
    active: boolean;
    onPress: () => void;
}

interface Props {
    /** The four destinations, in order; the add action sits between the second and the third. */
    items: readonly NavItem[];
    addLabel: string;
    onAdd: () => void;
    /** Read-only households cannot add expenses (BR-HH-10): the action is shown disabled. */
    addDisabled?: boolean;
    /** Bottom safe-area inset, added under the bar. */
    bottomInset: number;
}

/**
 * Bottom navigation of the approved design: Accueil, Dépenses, the central add action, Budget,
 * Paramètres. Presentational: the tab shell adapts the router state to it.
 */
export function BottomNav({ items, addLabel, onAdd, addDisabled, bottomInset }: Props) {
    const colors = useColors();
    const half = Math.ceil(items.length / 2);
    const renderItem = (item: NavItem) => {
        const tint = item.active ? colors.primary : colors.textSecondary;
        return (
            <Pressable
                key={item.key}
                testID={`tab-${item.key}`}
                accessibilityRole="tab"
                accessibilityLabel={item.label}
                accessibilityState={{ selected: item.active }}
                onPress={item.onPress}
                style={styles.item}
            >
                <Icon name={item.icon} size="lg" color={tint} />
                <Text
                    variant="label"
                    weight={item.active ? "semibold" : "medium"}
                    numberOfLines={1}
                    style={{ color: tint }}
                >
                    {item.label}
                </Text>
            </Pressable>
        );
    };
    return (
        <View
            accessibilityRole="tablist"
            style={[
                styles.bar,
                {
                    backgroundColor: colors.card,
                    borderTopColor: colors.surface,
                    height: size.tabBar + bottomInset,
                    paddingBottom: bottomInset,
                },
            ]}
        >
            {items.slice(0, half).map(renderItem)}
            <View style={styles.item}>
                <Pressable
                    testID="tab-add"
                    accessibilityRole="button"
                    accessibilityLabel={addLabel}
                    accessibilityState={{ disabled: !!addDisabled }}
                    disabled={addDisabled}
                    onPress={onAdd}
                    style={({ pressed }) => [
                        styles.add,
                        elevation.floating,
                        {
                            backgroundColor: colors.primary,
                            shadowColor: colors.primary,
                            opacity: addDisabled
                                ? opacity.disabled
                                : pressed
                                  ? opacity.pressed
                                  : opacity.opaque,
                        },
                    ]}
                >
                    <Icon name="add" size="lg" color={colors.onPrimary} />
                </Pressable>
            </View>
            {items.slice(half).map(renderItem)}
        </View>
    );
}

const styles = StyleSheet.create({
    bar: {
        flexDirection: "row",
        alignItems: "center",
        borderTopWidth: borderWidth.hairline,
        paddingHorizontal: spacing.xs,
    },
    item: {
        flex: 1,
        minHeight: minTouchTarget,
        alignItems: "center",
        justifyContent: "center",
        gap: spacing.xs,
    },
    add: {
        // Raised above the bar, as in the approved navigation.
        transform: [{ translateY: -spacing.md }],
        width: size.fab,
        height: size.fab,
        borderRadius: radius.pill,
        alignItems: "center",
        justifyContent: "center",
    },
});
