import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { configure, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { HouseholdProvider, useHouseholdState } from "@/features/household/HouseholdProvider";
import { renderUi } from "@/shared/ui/testing";
import { ANALYTICS_QUERY_KEY } from "./analyticsApi";
import { Dashboard } from "./Dashboard";

const mockPush = jest.fn();
jest.mock("expo-router", () => ({ useRouter: () => ({ push: mockPush }) }));

import { formatMoney } from "@/shared/money/money";
const fmt = (amount: string) => formatMoney({ amount, currency: "EUR" });
const eur = (amount: string) => ({ amount, currency: "EUR" });
const GROCERIES = "019a0000-0000-7000-8000-000000000001";
const OTHER = "019a0000-0000-7000-8000-000000000012";

const household = {
    id: "11111111-1111-7111-8111-111111111111",
    name: "Home",
    currency: "EUR",
    timezone: "Europe/Paris",
    periodStartDay: 1,
    status: "ACTIVE",
    version: 1,
};

const categories = {
    items: [
        { id: GROCERIES, systemCode: "GROCERIES", type: "SYSTEM" },
        { id: OTHER, name: "Pets", type: "CUSTOM" },
    ],
};

const householdAnalytics = {
    scope: "HOUSEHOLD",
    periodStart: "2026-03-01",
    periodEnd: "2026-04-01",
    total: eur("1234.50"),
    budget: {
        limit: eur("2000.00"),
        consumed: eur("1234.50"),
        remaining: eur("765.50"),
        percentage: "61.7",
        status: "ON_TRACK",
    },
    previousPeriod: { difference: eur("-40.00"), changePercentage: "-3.1", total: eur("1274.50") },
    categories: [
        { categoryId: GROCERIES, total: eur("900.00"), percentage: "72.9" },
        { categoryId: OTHER, total: eur("334.50"), percentage: "27.1" },
    ],
    negativeCategories: [
        { categoryId: "019a0000-0000-7000-8000-000000000099", total: eur("-5.00") },
    ],
    members: [],
};

const personalAnalytics = {
    ...householdAnalytics,
    scope: "PERSONAL",
    total: eur("77.00"),
    budget: undefined,
    previousPeriod: undefined,
    negativeCategories: [],
    categories: [{ categoryId: OTHER, total: eur("77.00"), percentage: "100.0" }],
};

type Handler = (url: URL) => { status: number; body: unknown };

function mockApi(handler: Handler) {
    const calls: string[] = [];
    jest.spyOn(globalThis, "fetch").mockImplementation((async (input: Request | string) => {
        const url = new URL(typeof input === "string" ? input : input.url, "http://localhost");
        calls.push(url.pathname + url.search);
        const { status, body } = handler(url);
        return new Response(JSON.stringify(body), {
            status,
            headers: {
                "Content-Type": status >= 400 ? "application/problem+json" : "application/json",
            },
        });
    }) as unknown as typeof fetch);
    return calls;
}

const defaultHandler: Handler = (url) => {
    if (url.pathname === "/api/v1/households/me") return { status: 200, body: household };
    if (url.pathname === "/api/v1/categories") return { status: 200, body: categories };
    if (url.searchParams.get("scope") === "PERSONAL")
        return { status: 200, body: personalAnalytics };
    return { status: 200, body: householdAnalytics };
};

/** Mirrors the app gate: the dashboard is only mounted once the household is ready. */
function ReadyGate() {
    const { state } = useHouseholdState();
    return state.kind === "ready" ? <Dashboard /> : null;
}

let client: QueryClient;
async function mount() {
    client = new QueryClient();
    await renderUi(
        <QueryClientProvider client={client}>
            <HouseholdProvider now={() => new Date("2026-03-15T10:00:00Z")}>
                <ReadyGate />
            </HouseholdProvider>
        </QueryClientProvider>,
    );
}

beforeEach(() => mockPush.mockClear());
// The first render cold-starts the module graph; keep async queries robust on loaded CI machines.
configure({ asyncUtilTimeout: 5000 });

afterEach(() => jest.restoreAllMocks());

describe("dashboard", () => {
    test("BR-ANA-01 / BR-SCP-03: loaded state shows server figures verbatim with the scope label", async () => {
        const calls = mockApi(defaultHandler);
        await mount();
        expect(await screen.findByText(fmt("1234.50"))).toBeTruthy();
        expect(screen.getByText(fmt("765.50"))).toBeTruthy();
        expect(screen.getByText(/-3\.1 %/)).toBeTruthy();
        expect(screen.getAllByText("Household").length).toBeGreaterThan(0);
        expect(screen.getByText("Groceries")).toBeTruthy();
        expect(screen.getByText("Pets")).toBeTruthy();
        expect(screen.getByText(/72\.9 %/)).toBeTruthy();
        expect(screen.getByTestId("period-header").children.join("")).toMatch(/2026/);
        expect(calls).toContain("/api/v1/analytics/periods/2026-03-01?scope=HOUSEHOLD");
    });

    test("BR-ANA-05: chart bars and list show the same server percentages, with a text alternative", async () => {
        mockApi(defaultHandler);
        await mount();
        const chart = await screen.findByTestId("category-chart");
        expect(chart.props.accessibilityLabel).toContain("Groceries");
        expect(chart.props.accessibilityLabel).toContain("72.9 %");
        const bars = screen.getAllByTestId("category-bar", { hidden: true });
        expect(
            bars.map(
                (b) => (b.props.style as { width: string }[]).flat().find((s) => s?.width)?.width,
            ),
        ).toEqual(["72.9%", "27.1%"]);
    });

    test("BR-ANA-06: negative categories are listed as provided and not charted", async () => {
        mockApi(defaultHandler);
        await mount();
        await screen.findByText(fmt("1234.50"));
        expect(screen.getByText("Net refunds")).toBeTruthy();
        expect(screen.getAllByTestId("category-bar", { hidden: true })).toHaveLength(2);
    });

    test("BR-BUD-02: household without a budget shows the Set a budget call to action", async () => {
        mockApi((url) =>
            url.pathname.includes("analytics")
                ? { status: 200, body: { ...householdAnalytics, budget: undefined } }
                : defaultHandler(url),
        );
        await mount();
        fireEvent.press(await screen.findByRole("button", { name: "Set a budget" }));
        expect(mockPush).toHaveBeenCalledWith("/budget");
    });

    test("empty period shows the Add an expense empty state and no chart", async () => {
        mockApi((url) =>
            url.pathname.includes("analytics")
                ? {
                      status: 200,
                      body: {
                          ...householdAnalytics,
                          total: eur("0.00"),
                          categories: [],
                          negativeCategories: [],
                          budget: undefined,
                      },
                  }
                : defaultHandler(url),
        );
        await mount();
        fireEvent.press(await screen.findByRole("button", { name: "Add an expense" }));
        expect(mockPush).toHaveBeenCalledWith("/add-expense");
        expect(screen.queryByTestId("category-chart")).toBeNull();
    });

    test("BR-SCP-01/02/03: scope toggle switches to personal data, labelled, without a budget block", async () => {
        const calls = mockApi(defaultHandler);
        await mount();
        await screen.findByText(fmt("1234.50"));
        fireEvent.press(screen.getByRole("radio", { name: "Personal" }));
        expect(await screen.findByText(fmt("77.00"))).toBeTruthy();
        expect(screen.queryByText(fmt("1234.50"))).toBeNull();
        expect(screen.queryByText("Budget remaining")).toBeNull();
        expect(screen.getAllByText("Personal").length).toBeGreaterThan(1);
        expect(
            screen.getByRole("radio", { name: "Personal" }).props.accessibilityState.selected,
        ).toBe(true);
        expect(calls).toContain("/api/v1/analytics/periods/2026-03-01?scope=PERSONAL");
    });

    test("tapping a category opens the expense list filtered by category and period", async () => {
        mockApi(defaultHandler);
        await mount();
        fireEvent.press(await screen.findByTestId(`category-row-${GROCERIES}`));
        expect(mockPush).toHaveBeenCalledWith({
            pathname: "/expenses",
            params: { categoryId: GROCERIES, periodStart: "2026-03-01" },
        });
    });

    test("partial error: failing categories do not blank the other blocks and can be retried", async () => {
        let failing = true;
        mockApi((url) =>
            url.pathname === "/api/v1/categories" && failing
                ? { status: 500, body: { code: "INTERNAL_ERROR", status: 500, title: "x" } }
                : defaultHandler(url),
        );
        await mount();
        expect(await screen.findByText(fmt("1234.50"))).toBeTruthy();
        expect(await screen.findByText("Retry")).toBeTruthy();
        failing = false;
        fireEvent.press(screen.getByText("Retry"));
        expect(await screen.findByText("Groceries")).toBeTruthy();
    });

    test("analytics failure shows an error state with retry (e.g. 404 period)", async () => {
        mockApi((url) =>
            url.pathname.includes("analytics")
                ? {
                      status: 404,
                      body: { code: "ANALYTICS_PERIOD_NOT_FOUND", status: 404, title: "x" },
                  }
                : defaultHandler(url),
        );
        await mount();
        expect(await screen.findByText("Retry")).toBeTruthy();
    });

    test("cache invalidation after an expense change reloads the dashboard", async () => {
        const calls = mockApi(defaultHandler);
        await mount();
        await screen.findByText(fmt("1234.50"));
        const before = calls.filter((c) => c.includes("/analytics/")).length;
        await client.invalidateQueries({ queryKey: ANALYTICS_QUERY_KEY });
        await waitFor(() =>
            expect(calls.filter((c) => c.includes("/analytics/")).length).toBeGreaterThan(before),
        );
    });
});
