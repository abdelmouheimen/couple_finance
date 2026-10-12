import { Tabs, useRouter } from "expo-router";
import { type ComponentProps } from "react";
import { StyleSheet, View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { useIsReadOnly } from "@/features/household/HouseholdProvider";
import { ReadOnlyBanner } from "@/features/household/ReadOnlyBanner";
import { strings } from "@/shared/i18n/strings";
import { BottomNav, type NavItem } from "@/shared/ui";
import { type IconName } from "@/shared/ui/theme/icons";

type BottomTabBarProps = Parameters<NonNullable<ComponentProps<typeof Tabs>["tabBar"]>>[0];

const TABS: Readonly<Record<string, { label: string; icon: IconName }>> = {
    index: { label: strings.tabs.home, icon: "home" },
    expenses: { label: strings.tabs.expenses, icon: "expenses" },
    budget: { label: strings.tabs.budget, icon: "budget" },
    settings: { label: strings.tabs.settings, icon: "settings" },
};

/** Adapts the router's tab state to the approved bottom navigation (with its central add action). */
function TabBar({ state, navigation }: BottomTabBarProps) {
    const router = useRouter();
    const insets = useSafeAreaInsets();
    const readOnly = useIsReadOnly();
    const items: NavItem[] = state.routes.flatMap((route, index) => {
        const tab = TABS[route.name];
        if (!tab) return [];
        const active = state.index === index;
        return [
            {
                key: route.name,
                label: tab.label,
                icon: tab.icon,
                active,
                onPress: () => {
                    const event = navigation.emit({
                        type: "tabPress",
                        target: route.key,
                        canPreventDefault: true,
                    });
                    if (!active && !event.defaultPrevented) navigation.navigate(route.name);
                },
            },
        ];
    });
    return (
        <BottomNav
            items={items}
            addLabel={strings.addExpense}
            addDisabled={readOnly}
            onAdd={() => router.push("/add-expense")}
            bottomInset={insets.bottom}
        />
    );
}

/** Tab shell: the four destinations and the central "add expense" action of the approved design. */
export default function TabsLayout() {
    return (
        <View style={styles.flex}>
            <ReadOnlyBanner />
            <Tabs tabBar={(props) => <TabBar {...props} />} screenOptions={{ headerShown: false }}>
                <Tabs.Screen name="index" options={{ title: strings.tabs.home }} />
                <Tabs.Screen name="expenses" options={{ title: strings.tabs.expenses }} />
                <Tabs.Screen name="budget" options={{ title: strings.tabs.budget }} />
                <Tabs.Screen name="settings" options={{ title: strings.tabs.settings }} />
            </Tabs>
        </View>
    );
}

const styles = StyleSheet.create({ flex: { flex: 1 } });
