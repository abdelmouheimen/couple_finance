import { Tabs, usePathname, useRouter } from "expo-router";
import { type ColorValue, Pressable, StyleSheet, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useIsReadOnly } from "@/features/household/HouseholdProvider";
import { ReadOnlyBanner } from "@/features/household/ReadOnlyBanner";
import { strings } from "@/shared/i18n/strings";
import { showAddExpenseAction } from "@/shared/navigation/addExpenseAction";
import { Icon, Text, useColors } from "@/shared/ui";
import {
    type IconName,
    elevation,
    fabSize,
    minTouchTarget,
    radius,
    spacing,
} from "@/shared/ui/theme/tokens";

function tabIcon(name: IconName) {
    // eslint-disable-next-line react/display-name -- small factory for tab icons
    return ({ color }: { color: ColorValue }) => <Icon name={name} color={String(color)} />;
}

/** Tab shell. The primary "Add expense" action floats above the tab bar on every tab (thumb reach). */
export default function TabsLayout() {
    const colors = useColors();
    const router = useRouter();
    const insets = useSafeAreaInsets();
    const readOnly = useIsReadOnly();
    const pathname = usePathname();
    // Explicit, deterministic tab bar height so the FAB offset never relies on a guess.
    const tabBarHeight = minTouchTarget + spacing.lg + insets.bottom;
    return (
        <View style={styles.flex}>
            <ReadOnlyBanner />
            <Tabs
                screenOptions={{
                    headerShown: false,
                    tabBarActiveTintColor: colors.primary,
                    tabBarInactiveTintColor: colors.textSecondary,
                    tabBarStyle: {
                        backgroundColor: colors.surface,
                        height: tabBarHeight,
                        paddingBottom: insets.bottom,
                    },
                    tabBarItemStyle: { minHeight: minTouchTarget },
                }}
            >
                <Tabs.Screen
                    name="index"
                    options={{ title: strings.tabs.home, tabBarIcon: tabIcon("home") }}
                />
                <Tabs.Screen
                    name="expenses"
                    options={{ title: strings.tabs.expenses, tabBarIcon: tabIcon("expenses") }}
                />
                <Tabs.Screen
                    name="budget"
                    options={{ title: strings.tabs.budget, tabBarIcon: tabIcon("budget") }}
                />
                <Tabs.Screen
                    name="settings"
                    options={{ title: strings.tabs.settings, tabBarIcon: tabIcon("settings") }}
                />
            </Tabs>
            {readOnly || !showAddExpenseAction(pathname) ? null : (
                <Pressable
                    accessibilityRole="button"
                    accessibilityLabel={strings.addExpense}
                    onPress={() => router.push("/add-expense")}
                    style={[
                        styles.fab,
                        elevation.floating,
                        {
                            backgroundColor: colors.primary,
                            bottom: tabBarHeight + spacing.md,
                        },
                    ]}
                >
                    <Icon name="add" size="lg" color={colors.onPrimary} />
                    <Text bold style={{ color: colors.onPrimary }}>
                        {strings.addExpense}
                    </Text>
                </Pressable>
            )}
        </View>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    fab: {
        position: "absolute",
        right: spacing.lg,
        minWidth: fabSize,
        height: fabSize,
        paddingHorizontal: spacing.lg,
        gap: spacing.sm,
        flexDirection: "row",
        borderRadius: radius.pill,
        alignItems: "center",
        justifyContent: "center",
    },
});
