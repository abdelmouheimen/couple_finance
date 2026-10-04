import { Tabs, useRouter } from "expo-router";
import { type ColorValue, Pressable, StyleSheet, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { strings } from "@/shared/i18n/strings";
import { Icon, useColors } from "@/shared/ui";
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
    return (
        <View style={styles.flex}>
            <Tabs
                screenOptions={{
                    headerShown: false,
                    tabBarActiveTintColor: colors.primary,
                    tabBarInactiveTintColor: colors.textSecondary,
                    tabBarStyle: { backgroundColor: colors.surface, minHeight: minTouchTarget },
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
            </Tabs>
            <Pressable
                accessibilityRole="button"
                accessibilityLabel={strings.addExpense}
                onPress={() => router.push("/add-expense")}
                style={[
                    styles.fab,
                    elevation.floating,
                    {
                        backgroundColor: colors.primary,
                        bottom: insets.bottom + minTouchTarget + spacing.xl,
                    },
                ]}
            >
                <Icon name="add" size="lg" color={colors.onPrimary} />
            </Pressable>
        </View>
    );
}

const styles = StyleSheet.create({
    flex: { flex: 1 },
    fab: {
        position: "absolute",
        right: spacing.lg,
        width: fabSize,
        height: fabSize,
        borderRadius: radius.pill,
        alignItems: "center",
        justifyContent: "center",
    },
});
