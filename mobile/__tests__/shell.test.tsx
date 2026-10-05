import { fireEvent, screen, within } from "@testing-library/react-native";
import { showAddExpenseAction } from "@/shared/navigation/addExpenseAction";
import { renderRouter } from "expo-router/testing-library";
import TabsLayout from "../app/(tabs)/_layout";
import Home from "../app/(tabs)/index";
import Expenses from "../app/(tabs)/expenses";
import Budget from "../app/(tabs)/budget";
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
// The dashboard needs the household/query providers, covered by its own tests.
jest.mock("@/features/analytics/Dashboard", () => ({ Dashboard: () => null }));

// Navigation-only test: the budget screen needs the household provider.
jest.mock("@/features/budget/BudgetScreen", () => ({ BudgetScreen: () => null }));

const routes = {
    "(tabs)/_layout": TabsLayout,
    "(tabs)/index": Home,
    "(tabs)/expenses": Expenses,
    "(tabs)/budget": Budget,
    "add-expense": AddExpense,
};

describe("tab shell navigation", () => {
    test.each(["/", "/expenses"])(
        "the labelled Add expense action is reachable from %s",
        async (path) => {
            await renderRouter(routes, { initialUrl: path });
            await fireEvent.press(await screen.findByRole("button", { name: /add expense/i }));
            expect(await screen.findByText(/quick add screen/i)).toBeTruthy();
        },
    );

    test("the Add expense action is visibly labelled", async () => {
        await renderRouter(routes, { initialUrl: "/" });
        const action = await screen.findByRole("button", { name: "Add expense" });
        expect(within(action).getByText("Add expense")).toBeTruthy();
    });

    test("the Add expense action is hidden on Budget so it never competes with Edit budget", async () => {
        await renderRouter(routes, { initialUrl: "/budget" });
        await screen.findByRole("button", { name: /budget, tab/i });
        expect(screen.queryByRole("button", { name: "Add expense" })).toBeNull();
    });
});

describe("showAddExpenseAction", () => {
    test.each([
        ["/", true],
        ["/expenses", true],
        ["/budget", false],
        ["/settings", false],
    ])("%s -> %s", (path, expected) => {
        expect(showAddExpenseAction(path)).toBe(expected);
    });
});
