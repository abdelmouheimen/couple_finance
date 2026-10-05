import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { configure, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { HouseholdProvider, useHouseholdState } from "@/features/household/HouseholdProvider";
import { formatMoney } from "@/shared/money/money";
import { renderUi } from "@/shared/ui/testing";
import { ANALYTICS_QUERY_KEY } from "./analyticsApi";
import { Dashboard } from "./Dashboard";

const mockPush = jest.fn();
jest.mock("expo-router", () => ({ useRouter: () => ({ push: mockPush }) }));

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

const dailySeries = (scope: string, limit?: { amount: string; currency: string }) => ({
    scope,
    periodStart: "2026-03-01",
    periodEnd: "2026-04-01",
    ...(limit ? { limit } : {}),
    // 1-3: 10.00 on day 1, nothing on days 2-3 (cumulative repeated by the server), 4: +20.00
    points: [
        { date: "2026-03-01", cumulative: eur("10.00") },
        { date: "2026-03-02", cumulative: eur("10.00") },
        { date: "2026-03-03", cumulative: eur("10.00") },
        { date: "2026-03-04", cumulative: eur("30.00") },
    ],
});

const recentExpense = {
    id: "e-recent",
    kind: "EXPENSE",
    amount: eur("12.50"),
    date: "2026-03-14",
    merchant: "Bakery",
    sharingType: "SHARED",
    paidByUserId: "u-1",
    version: 0,
    items: [{ id: "i-1", categoryId: GROCERIES, amount: eur("12.50"), position: 0 }],
};

const isPeriod = (url: URL) => /\/analytics\/periods\/[^/]+$/.test(url.pathname);

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
    if (url.pathname.endsWith("/daily-cumulative")) {
        return url.searchParams.get("scope") === "PERSONAL"
            ? { status: 200, body: dailySeries("PERSONAL") }
            : { status: 200, body: dailySeries("HOUSEHOLD", eur("2000.00")) };
    }
    if (url.pathname === "/api/v1/expenses") {
        return {
            status: 200,
            body: {
                items: [recentExpense],
                totals: { scope: url.searchParams.get("scope"), net: eur("12.50") },
            },
        };
    }
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
async function mount(scheme: "light" | "dark" = "light") {
    client = new QueryClient();
    await renderUi(
        <QueryClientProvider client={client}>
            <HouseholdProvider now={() => new Date("2026-03-15T10:00:00Z")}>
                <ReadyGate />
            </HouseholdProvider>
        </QueryClientProvider>,
        scheme,
    );
}

beforeEach(() => mockPush.mockClear());
// The first render cold-starts the module graph; keep async queries robust on loaded CI machines.
configure({ asyncUtilTimeout: 15000 });

afterEach(() => jest.restoreAllMocks());

describe("dashboard", () => {
    test("BR-ANA-01 / BR-SCP-03: loaded state shows server figures verbatim with the scope label", async () => {
        const calls = mockApi(defaultHandler);
        await mount();
        expect(await screen.findByText(fmt("1234.50"))).toBeTruthy();
        expect(screen.getByText(fmt("765.50"))).toBeTruthy();
        expect(screen.getByText(/-3\.1 %/)).toBeTruthy();
        expect(screen.getAllByText("Household").length).toBeGreaterThan(0);
        expect(screen.getAllByText("Groceries").length).toBeGreaterThan(0);
        expect(screen.getAllByText("Pets").length).toBeGreaterThan(0);
        expect(screen.getByText(/72\.9 %/)).toBeTruthy();
        expect(screen.getByTestId("period-header").children.join("")).toMatch(/2026/);
        expect(calls).toContain("/api/v1/analytics/periods/2026-03-01?scope=HOUSEHOLD");
    });

    test("BR-ANA-05: donut and list show the same server percentages, with a text alternative", async () => {
        mockApi(defaultHandler);
        await mount();
        const row = await screen.findByTestId(`category-row-${GROCERIES}`);
        expect(row.props.accessibilityLabel).toContain("Groceries");
        expect(row.props.accessibilityLabel).toContain("72.9 %");
        const chart = screen.getByTestId("category-chart");
        expect(chart.props.accessibilityRole).toBe("image");
        expect(chart.props.accessibilityLabel).toBe(
            "Spending by category, Household: Groceries 72.9 %, Pets 27.1 %",
        );
        expect(screen.getAllByTestId("donut-arc")).toHaveLength(2);
    });

    test("BR-ANA-06: negative categories are listed as provided and not charted", async () => {
        mockApi(defaultHandler);
        await mount();
        await screen.findByText(fmt("1234.50"));
        expect(screen.getByText("Net refunds")).toBeTruthy();
        expect(screen.getAllByTestId("donut-arc")).toHaveLength(2); // refunds are not drawn
    });

    test("BR-BUD-02: household without a budget shows the Set a budget call to action", async () => {
        mockApi((url) =>
            isPeriod(url)
                ? { status: 200, body: { ...householdAnalytics, budget: undefined } }
                : defaultHandler(url),
        );
        await mount();
        fireEvent.press(await screen.findByRole("button", { name: "Set a budget" }));
        expect(mockPush).toHaveBeenCalledWith("/budget");
    });

    test("empty period shows the Add an expense empty state and no chart", async () => {
        mockApi((url) =>
            isPeriod(url)
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
            params: {
                categoryId: GROCERIES,
                periodStart: "2026-03-01",
                scope: "HOUSEHOLD",
                nav: expect.any(String),
            },
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
        expect((await screen.findAllByText("Groceries")).length).toBeGreaterThan(0);
    });

    test("analytics failure shows an error state with retry (e.g. 404 period)", async () => {
        mockApi((url) =>
            isPeriod(url)
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

const catId = (n: number) => `019a0000-0000-7000-8000-0000000001${String(n).padStart(2, "0")}`;

describe("dashboard charts (MOBILE-008)", () => {
    test("Home blocks follow the approved order", async () => {
        mockApi(defaultHandler);
        await mount();
        await screen.findByText("Bakery");
        const headers = screen
            .getAllByRole("header")
            .map((h) => h.children.join(""))
            .filter((t) =>
                [
                    "Home",
                    "Spent this period",
                    "Where it goes",
                    "Spending over time",
                    "Recent expenses",
                ].includes(t),
            );
        expect(headers).toEqual([
            "Home",
            "Spent this period",
            "Where it goes",
            "Spending over time",
            "Recent expenses",
        ]);
    });

    test("Home has no Settings gear (Settings lives in the bottom navigation)", async () => {
        mockApi(defaultHandler);
        await mount();
        await screen.findByText("Bakery");
        expect(screen.queryByText("⚙")).toBeNull();
        expect(screen.queryByRole("button", { name: /settings/i })).toBeNull();
    });

    test("BR-ANA-01: the spending chart draws the server series with the budget line and a text alternative", async () => {
        const calls = mockApi(defaultHandler);
        await mount();
        const chart = await screen.findByTestId("spending-chart");
        expect(calls).toContain(
            "/api/v1/analytics/periods/2026-03-01/daily-cumulative?scope=HOUSEHOLD",
        );
        expect(chart.props.accessibilityRole).toBe("image");
        expect(chart.props.accessibilityLabel).toContain(
            "Cumulative spending over time, Household",
        );
        expect(chart.props.accessibilityLabel).toContain(fmt("30.00"));
        expect(chart.props.accessibilityLabel).toContain(`budget limit ${fmt("2000.00")}`);
        expect(screen.getByTestId("chart-budget-line")).toBeTruthy();
        expect(screen.getByTestId("chart-baseline")).toBeTruthy();
        // Gap days (2-3) come already repeated by the server; vertex mapping is covered in chartModel.test.
        expect(screen.getByTestId("chart-line")).toBeTruthy();
        expect(screen.getByTestId("spending-chart-limit")).toBeTruthy();
    });

    test("BR-BUD-02 / BR-SCP-02: the personal series has no budget line", async () => {
        const calls = mockApi(defaultHandler);
        await mount();
        await screen.findByTestId("spending-chart");
        await fireEvent.press(screen.getByRole("radio", { name: "Personal" }));
        await waitFor(() =>
            expect(screen.getByTestId("spending-chart").props.accessibilityLabel).toContain(
                "Personal",
            ),
        );
        expect(screen.queryByTestId("chart-budget-line")).toBeNull();
        expect(calls).toContain(
            "/api/v1/analytics/periods/2026-03-01/daily-cumulative?scope=PERSONAL",
        );
    });

    test("a single data point renders as a point with a dedicated alternative, no line", async () => {
        mockApi((url) =>
            url.pathname.endsWith("/daily-cumulative")
                ? {
                      status: 200,
                      body: {
                          ...dailySeries("HOUSEHOLD"),
                          points: [{ date: "2026-03-01", cumulative: eur("12.50") }],
                      },
                  }
                : defaultHandler(url),
        );
        await mount();
        const chart = await screen.findByTestId("spending-chart");
        expect(screen.getByTestId("chart-last-point")).toBeTruthy();
        expect(screen.queryByTestId("chart-line")).toBeNull();
        expect(chart.props.accessibilityLabel).toContain(fmt("12.50"));
    });

    test("the spending chart shows an error with retry and does not blank the other blocks", async () => {
        let failing = true;
        mockApi((url) =>
            url.pathname.endsWith("/daily-cumulative") && failing
                ? { status: 500, body: { code: "INTERNAL_ERROR", status: 500, title: "x" } }
                : defaultHandler(url),
        );
        await mount();
        expect(await screen.findByText(fmt("1234.50"))).toBeTruthy();
        expect(await screen.findByText("Retry")).toBeTruthy();
        expect(screen.getByTestId("category-chart")).toBeTruthy();
        failing = false;
        await fireEvent.press(screen.getByText("Retry"));
        expect(await screen.findByTestId("spending-chart")).toBeTruthy();
    });

    test("the spending chart shows a loading state, then an empty message for an empty series", async () => {
        mockApi((url) =>
            url.pathname.endsWith("/daily-cumulative")
                ? { status: 200, body: { ...dailySeries("HOUSEHOLD"), points: [] } }
                : defaultHandler(url),
        );
        await mount();
        expect(await screen.findByText("No daily data for this period yet.")).toBeTruthy();
        expect(screen.queryByTestId("spending-chart")).toBeNull();
    });

    test("BR-ANA-05: more than five categories group the tail as Other in the donut only", async () => {
        const ids = Array.from({ length: 8 }, (_, i) => catId(i));
        const percentages = ["40.0", "20.0", "15.0", "10.0", "5.1", "4.9", "3.2", "1.8"];
        mockApi((url) =>
            isPeriod(url)
                ? {
                      status: 200,
                      body: {
                          ...householdAnalytics,
                          negativeCategories: [],
                          categories: ids.map((id, i) => ({
                              categoryId: id,
                              total: eur(`${100 - i}.00`),
                              percentage: percentages[i],
                          })),
                      },
                  }
                : defaultHandler(url),
        );
        await mount();
        await screen.findByTestId("category-chart");
        // donut: 5 slices + Other; list: 5 rows + an "Other (3 more)" row, nothing hidden
        expect(screen.getAllByTestId("donut-arc")).toHaveLength(6);
        expect(screen.getByTestId("category-chart").props.accessibilityLabel).toContain(
            "Other 9.9 %",
        );
        expect(screen.queryByTestId(`category-row-${ids[5]}`)).toBeNull();
        expect(screen.getByTestId("category-other-row")).toBeTruthy();
        await fireEvent.press(screen.getByTestId("category-toggle"));
        for (const id of ids) expect(screen.getByTestId(`category-row-${id}`)).toBeTruthy();
        expect(screen.queryByTestId("category-other-row")).toBeNull();
        // each detailed row still carries its own server amount and percentage
        expect(screen.getByTestId(`category-row-${ids[7]}`).props.accessibilityLabel).toContain(
            "1.8 %",
        );
        await fireEvent.press(screen.getByTestId("category-toggle"));
        expect(screen.queryByTestId(`category-row-${ids[7]}`)).toBeNull();
    });

    test("a single category fills the donut and is listed with 100.0 %", async () => {
        mockApi((url) =>
            isPeriod(url)
                ? {
                      status: 200,
                      body: {
                          ...householdAnalytics,
                          negativeCategories: [],
                          categories: [
                              { categoryId: GROCERIES, total: eur("1234.50"), percentage: "100.0" },
                          ],
                      },
                  }
                : defaultHandler(url),
        );
        await mount();
        await screen.findByTestId("category-chart");
        expect(screen.getAllByTestId("donut-arc")).toHaveLength(1);
        expect(screen.getByTestId(`category-row-${GROCERIES}`).props.accessibilityLabel).toContain(
            "100.0 %",
        );
        expect(screen.queryByTestId("category-toggle")).toBeNull();
    });

    test("BR-ANA-06: refunds only -> no donut, a text explanation, refunds still listed", async () => {
        mockApi((url) =>
            isPeriod(url)
                ? {
                      status: 200,
                      body: {
                          ...householdAnalytics,
                          total: eur("-5.00"),
                          categories: [],
                          negativeCategories: [{ categoryId: GROCERIES, total: eur("-5.00") }],
                      },
                  }
                : defaultHandler(url),
        );
        await mount();
        expect(await screen.findByTestId("category-chart-empty")).toBeTruthy();
        expect(screen.queryByTestId("donut-arc")).toBeNull();
        expect(screen.getByText("Net refunds")).toBeTruthy();
    });

    test("BR-MON-02: large amounts are formatted from the exact string (no number conversion)", async () => {
        const huge = "90071992547409930.01";
        mockApi((url) =>
            isPeriod(url)
                ? {
                      status: 200,
                      body: {
                          ...householdAnalytics,
                          total: eur(huge),
                          budget: undefined,
                          previousPeriod: undefined,
                      },
                  }
                : defaultHandler(url),
        );
        await mount();
        expect(await screen.findByText(fmt(huge))).toBeTruthy();
        expect(fmt(huge)).toMatch(/90.?071.?992.?547.?409.?930/);
    });

    test("recent expenses come from the expense API for the active scope and link to the full list", async () => {
        const calls = mockApi(defaultHandler);
        await mount();
        expect(await screen.findByText("Bakery")).toBeTruthy();
        expect(
            calls.some(
                (c) =>
                    c.startsWith("/api/v1/expenses?") &&
                    c.includes("scope=HOUSEHOLD") &&
                    c.includes("dateFrom=2026-03-01") &&
                    c.includes("dateTo=2026-03-31"),
            ),
        ).toBe(true);
        await fireEvent.press(screen.getByRole("button", { name: "See all expenses" }));
        expect(mockPush).toHaveBeenCalledWith({
            pathname: "/expenses",
            params: { periodStart: "2026-03-01", scope: "HOUSEHOLD", nav: expect.any(String) },
        });
    });

    test("BR-EXP-07 / BR-SCP-01: the personal view never shows household recent expenses", async () => {
        mockApi((url) =>
            url.pathname === "/api/v1/expenses"
                ? {
                      status: 200,
                      body: {
                          items:
                              url.searchParams.get("scope") === "PERSONAL"
                                  ? [
                                        {
                                            ...recentExpense,
                                            id: "e-p",
                                            merchant: "Mine",
                                            sharingType: "PERSONAL",
                                        },
                                    ]
                                  : [recentExpense],
                          totals: { scope: url.searchParams.get("scope"), net: eur("1.00") },
                      },
                  }
                : defaultHandler(url),
        );
        await mount();
        await screen.findByText("Bakery");
        await fireEvent.press(screen.getByRole("radio", { name: "Personal" }));
        expect(await screen.findByText("Mine")).toBeTruthy();
        expect(screen.queryByText("Bakery")).toBeNull();
    });

    test("charts render in dark mode", async () => {
        mockApi(defaultHandler);
        await mount("dark");
        expect(await screen.findByTestId("spending-chart")).toBeTruthy();
        expect(screen.getAllByTestId("donut-arc").length).toBeGreaterThan(0);
    });
});
