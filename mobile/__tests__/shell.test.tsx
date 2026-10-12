import { fireEvent, screen } from "@testing-library/react-native";
import { renderRouter } from "expo-router/testing-library";
import TabsLayout from "../app/(tabs)/_layout";
import Home from "../app/(tabs)/index";
import Expenses from "../app/(tabs)/expenses";
import Budget from "../app/(tabs)/budget";
import Settings from "../app/(tabs)/settings";
import AddExpense from "../app/add-expense";

// The feature screens need app providers (household, query client); the shell test covers navigation.
jest.mock("@/features/expense/QuickAddScreen", () => {
    const { Text } = jest.requireActual<typeof import("react-native")>("react-native");
    return { QuickAddScreen: () => <Text>quick add screen</Text> };
});
jest.mock("@/features/expense/ExpenseListScreen", () => {
    const { Text } = jest.requireActual<typeof import("react-native")>("react-native");
    return { ExpenseListScreen: () => <Text>expense list screen</Text> };
});
jest.mock("@/features/analytics/Dashboard", () => ({ Dashboard: () => null }));
jest.mock("@/features/budget/BudgetScreen", () => ({ BudgetScreen: () => null }));
jest.mock("@/features/settings/SettingsScreen", () => ({ SettingsScreen: () => null }));

const routes = {
    "(tabs)/_layout": TabsLayout,
    "(tabs)/index": Home,
    "(tabs)/expenses": Expenses,
    "(tabs)/budget": Budget,
    "(tabs)/settings": Settings,
    "add-expense": AddExpense,
};

describe("bottom navigation (approved design)", () => {
    test("shows the four destinations in order with the central add action", async () => {
        await renderRouter(routes, { initialUrl: "/" });
        const tabs = await screen.findAllByRole("tab");
        expect(tabs.map((t) => t.props.accessibilityLabel)).toEqual([
            "Accueil",
            "Dépenses",
            "Budget",
            "Paramètres",
        ]);
        expect(screen.getByRole("button", { name: "Ajouter une dépense" })).toBeTruthy();
    });

    test.each(["/", "/expenses", "/budget", "/settings"])(
        "the add action is reachable from %s",
        async (path) => {
            await renderRouter(routes, { initialUrl: path });
            await fireEvent.press(
                await screen.findByRole("button", { name: "Ajouter une dépense" }),
            );
            expect(await screen.findByText(/quick add screen/i)).toBeTruthy();
        },
    );

    test("the active destination is exposed as selected", async () => {
        await renderRouter(routes, { initialUrl: "/expenses" });
        const tab = await screen.findByRole("tab", { name: "Dépenses" });
        expect(tab.props.accessibilityState).toMatchObject({ selected: true });
        expect(screen.getByRole("tab", { name: "Accueil" }).props.accessibilityState).toMatchObject(
            { selected: false },
        );
    });

    test("pressing a destination navigates to it", async () => {
        await renderRouter(routes, { initialUrl: "/" });
        await fireEvent.press(await screen.findByRole("tab", { name: "Dépenses" }));
        expect(await screen.findByText(/expense list screen/i)).toBeTruthy();
    });
});
