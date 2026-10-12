import { Redirect, useLocalSearchParams, useRouter } from "expo-router";
import { View } from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { categoryVisual } from "@/features/expense/categoryVisual";
import { ReceiptCapturePanel } from "@/features/receipt/ReceiptCapturePanel";
import { ReceiptReviewScreen } from "@/features/receipt/ReceiptReviewScreen";
import { strings } from "@/shared/i18n/strings";
import { BottomNav, type NavItem, Screen, Segmented } from "@/shared/ui";

/**
 * Design preview of the receipt screens (dev builds only; production builds redirect away). Receipt
 * capture and AI analysis are not implemented: these screens are rendered here with sample values so
 * their approved design can be reviewed and compared with the reference. Nothing is sent or stored.
 */
export default function DesignPreview() {
    const router = useRouter();
    const insets = useSafeAreaInsets();
    const { screen } = useLocalSearchParams<{ screen?: string }>();

    if (!__DEV__) return <Redirect href="/" />;

    const nothing = () => undefined;
    if (screen === "scan") {
        return (
            <Screen
                edges={["top", "left", "right", "bottom"]}
                title={strings.addExpense}
                onClose={() => router.back()}
            >
                <Segmented
                    label={strings.expense.entryMode}
                    value="SCAN"
                    onChange={nothing}
                    options={[
                        { value: "SCAN", label: strings.expense.modes.SCAN },
                        { value: "MANUAL", label: strings.expense.modes.MANUAL },
                        { value: "IMPORT", label: strings.expense.modes.IMPORT },
                    ]}
                />
                <ReceiptCapturePanel mode="SCAN" onUseManual={nothing} />
            </Screen>
        );
    }

    const items: NavItem[] = [
        { key: "index", label: strings.tabs.home, icon: "home", active: false, onPress: nothing },
        {
            key: "expenses",
            label: strings.tabs.expenses,
            icon: "expenses",
            active: false,
            onPress: nothing,
        },
        {
            key: "budget",
            label: strings.tabs.budget,
            icon: "budget",
            active: false,
            onPress: nothing,
        },
        {
            key: "settings",
            label: strings.tabs.settings,
            icon: "settings",
            active: false,
            onPress: nothing,
        },
    ];
    const groceries = { icon: "cart", systemCode: "GROCERIES" };
    return (
        <View style={{ flex: 1 }}>
            <ReceiptReviewScreen
                draft={{
                    merchant: "Carrefour",
                    dateLabel: new Date(Date.UTC(2026, 9, 4)).toLocaleDateString(undefined, {
                        dateStyle: "medium",
                        timeZone: "UTC",
                    }),
                    amount: { amount: "13.78", currency: "EUR" },
                    category: {
                        label: strings.categoryNames["GROCERIES"] ?? "",
                        visual: categoryVisual(groceries),
                    },
                    confidence: {
                        overall: "96",
                        merchant: "98",
                        date: "96",
                        amount: "99",
                        category: "92",
                    },
                    lineCount: 4,
                }}
                onBack={() => router.back()}
                onChangePhoto={nothing}
                onOpenLines={nothing}
                onConfirm={nothing}
            />
            <BottomNav
                items={items}
                addLabel={strings.addExpense}
                onAdd={nothing}
                bottomInset={insets.bottom}
            />
        </View>
    );
}
