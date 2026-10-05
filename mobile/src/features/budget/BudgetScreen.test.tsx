import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { configure, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { HouseholdProvider, useHouseholdState } from "@/features/household/HouseholdProvider";
import { formatMoney } from "@/shared/money/money";
import { renderUi } from "@/shared/ui/testing";
import { BudgetScreen } from "./BudgetScreen";

const household = (status: string) => ({
    id: "11111111-1111-7111-8111-111111111111",
    name: "Home",
    currency: "EUR",
    timezone: "Europe/Paris",
    periodStartDay: 1,
    status,
    version: 1,
});

const eur = (amount: string) => ({ amount, currency: "EUR" });
/** The app formats with the device locale; tests assert through the same formatter. */
const fmt = (amount: string) => formatMoney(eur(amount));
const CAT = "019a0000-0000-7000-8000-000000000001";
const categories = {
    items: [
        { id: CAT, systemCode: "GROCERIES", archived: false, sortOrder: 1, type: "SYSTEM" },
        {
            id: "019a0000-0000-7000-8000-000000000002",
            systemCode: "HOUSING",
            archived: false,
            sortOrder: 2,
            type: "SYSTEM",
        },
    ],
};

const budget = (over: object = {}) => ({
    id: "b1",
    periodStart: "2026-03-01",
    periodEnd: "2026-04-01",
    version: 3,
    categoryLimits: [{ categoryId: CAT, limit: eur("200.00") }],
    overallLimit: eur("500.00"),
    overallConsumption: {
        consumed: eur("412.50"),
        remaining: eur("87.50"),
        percentage: "82.5",
        status: "WARNING",
        scope: "HOUSEHOLD",
    },
    ...over,
});

const problem = (status: number, code: string) => ({ status, body: { code, status, title: "x" } });

type Handler = (req: Request) => { status: number; body: unknown };

function mockApi(handlers: Record<string, Handler>) {
    const calls: Request[] = [];
    jest.spyOn(globalThis, "fetch").mockImplementation((async (input: Request | string) => {
        const req = input as Request;
        calls.push(req.clone());
        const path = new URL(req.url).pathname;
        const handler = handlers[`${req.method} ${path}`];
        const { status, body } = handler ? handler(req) : problem(500, "INTERNAL_ERROR");
        return new Response(JSON.stringify(body), {
            status,
            headers: {
                "Content-Type": status >= 400 ? "application/problem+json" : "application/json",
            },
        });
    }) as unknown as typeof fetch);
    return calls;
}

const base = (status = "ACTIVE"): Record<string, Handler> => ({
    "GET /api/v1/categories": () => ({ status: 200, body: categories }),
    "GET /api/v1/households/me": () => ({ status: 200, body: household(status) }),
});

const withBudget = (b: unknown, status = "ACTIVE"): Record<string, Handler> => ({
    ...base(status),
    "GET /api/v1/budgets/2026-03-01": () => ({ status: 200, body: b }),
});

const noBudget = (status = "ACTIVE"): Record<string, Handler> => ({
    ...base(status),
    "GET /api/v1/budgets/2026-03-01": () => problem(404, "BUDGET_NOT_FOUND"),
});

function Gate() {
    const { state } = useHouseholdState();
    return state.kind === "ready" ? <BudgetScreen /> : null;
}

async function mount() {
    await renderUi(
        <QueryClientProvider client={new QueryClient()}>
            <HouseholdProvider now={() => new Date("2026-03-15T10:00:00Z")}>
                <Gate />
            </HouseholdProvider>
        </QueryClientProvider>,
    );
}

configure({ asyncUtilTimeout: 15000 });

afterEach(() => jest.restoreAllMocks());

describe("BudgetScreen", () => {
    test("BR-BUD-03/05/06, BR-MON-09: figures are shown exactly as returned, labelled HOUSEHOLD", async () => {
        mockApi(withBudget(budget()));
        await mount();
        expect(await screen.findByText("82.5 %")).toBeTruthy();
        expect(screen.getByText("Warning")).toBeTruthy();
        expect(screen.getByText("Remaining")).toBeTruthy();
        expect(screen.getByLabelText(`${fmt("87.50")}, Household`)).toBeTruthy();
        expect(screen.getByText(fmt("412.50"))).toBeTruthy();
        expect(screen.getByText("Groceries")).toBeTruthy();
        expect(screen.getByRole("progressbar").props.accessibilityValue.text).toBe(
            `82.5 percent of budget used, ${fmt("87.50")} remaining`,
        );
    });

    test("BR-BUD-05: EXCEEDED shows 'over by', a negative remaining, text and status", async () => {
        mockApi(
            withBudget(
                budget({
                    overallConsumption: {
                        consumed: eur("550.00"),
                        remaining: eur("-50.00"),
                        percentage: "110.0",
                        status: "EXCEEDED",
                        scope: "HOUSEHOLD",
                    },
                }),
            ),
        );
        await mount();
        expect(await screen.findByText("Remaining")).toBeTruthy();
        expect(screen.getByText("Exceeded")).toBeTruthy();
        expect(screen.getByText("110.0 %")).toBeTruthy();
        expect(screen.getByLabelText(`${fmt("-50.00")}, Household`)).toBeTruthy();
    });

    test("BR-BUD-01: ON_TRACK is labelled in text", async () => {
        mockApi(
            withBudget(
                budget({
                    overallConsumption: {
                        consumed: eur("100.00"),
                        remaining: eur("400.00"),
                        percentage: "20.0",
                        status: "ON_TRACK",
                        scope: "HOUSEHOLD",
                    },
                }),
            ),
        );
        await mount();
        expect(await screen.findByText("On track")).toBeTruthy();
    });

    test("BR-BUD-04: the server's categoryLimitsWarning is displayed, non-blocking", async () => {
        mockApi(
            withBudget(
                budget({
                    categoryLimitsWarning: {
                        categoryLimitsTotal: eur("600.00"),
                        overallLimit: eur("500.00"),
                    },
                }),
            ),
        );
        await mount();
        expect(await screen.findByText("Category limits exceed the overall limit")).toBeTruthy();
        expect(screen.getByText(fmt("600.00"), { exact: false })).toBeTruthy();
        expect(screen.getByText("Edit budget")).toBeTruthy();
    });

    test("404 BUDGET_NOT_FOUND shows the empty state with Set and Copy actions (not an error)", async () => {
        mockApi(noBudget());
        await mount();
        expect(await screen.findByText("No budget for this month")).toBeTruthy();
        expect(screen.getByText("Set budget")).toBeTruthy();
        expect(screen.getByText("Copy previous budget")).toBeTruthy();
        expect(screen.queryByText("Retry")).toBeNull();
    });

    test("404 BUDGET_PERIOD_NOT_FOUND is an error state with retry (no probing)", async () => {
        const calls = mockApi({
            ...base(),
            "GET /api/v1/budgets/2026-03-01": () => problem(404, "BUDGET_PERIOD_NOT_FOUND"),
        });
        await mount();
        expect(await screen.findByText("Retry")).toBeTruthy();
        expect(screen.queryByText("No budget for this month")).toBeNull();
        expect(calls.filter((c) => c.url.includes("/budgets/")).length).toBe(1);
    });

    test("copy previous is an explicit tap and the created budget is then shown", async () => {
        let created = false;
        const calls = mockApi({
            ...base(),
            "GET /api/v1/budgets/2026-03-01": () =>
                created ? { status: 200, body: budget() } : problem(404, "BUDGET_NOT_FOUND"),
            "POST /api/v1/budgets/2026-03-01/copy-previous": () => {
                created = true;
                return { status: 201, body: budget() };
            },
        });
        await mount();
        await screen.findByText("Copy previous budget");
        expect(calls.some((c) => c.method === "POST")).toBe(false);
        await fireEvent.press(screen.getByText("Copy previous budget"));
        expect(await screen.findByText("82.5 %")).toBeTruthy();
        expect(calls.some((c) => c.method === "POST")).toBe(true);
    });

    test("no previous budget: the server error is shown as a friendly message", async () => {
        mockApi({
            ...noBudget(),
            "POST /api/v1/budgets/2026-03-01/copy-previous": () =>
                problem(404, "PREVIOUS_BUDGET_NOT_FOUND"),
        });
        await mount();
        await fireEvent.press(await screen.findByText("Copy previous budget"));
        expect(
            await screen.findByText("There is no budget in the previous period to copy."),
        ).toBeTruthy();
    });

    test("BR-HH-10: a DISSOLVED household has no set or copy action", async () => {
        mockApi(noBudget("DISSOLVED"));
        await mount();
        expect(await screen.findByText("No budget for this month")).toBeTruthy();
        expect(screen.queryByText("Set budget")).toBeNull();
        expect(screen.queryByText("Copy previous budget")).toBeNull();
    });

    test("BR-HH-10: a DISSOLVED household cannot edit an existing budget", async () => {
        mockApi(withBudget(budget(), "DISSOLVED"));
        await mount();
        expect(await screen.findByText("82.5 %")).toBeTruthy();
        expect(screen.queryByText("Edit budget")).toBeNull();
    });

    test("BR-BUD-07: past periods are viewable and editable, with an audit note", async () => {
        mockApi({
            ...noBudget(),
            "GET /api/v1/budgets/2026-02-01": () => ({
                status: 200,
                body: budget({ periodStart: "2026-02-01", periodEnd: "2026-03-01" }),
            }),
        });
        await mount();
        await fireEvent.press(await screen.findByLabelText(/^Previous period/));
        expect(await screen.findByText("82.5 %")).toBeTruthy();
        expect(screen.getByText("This is a past period. Edits to it are audited.")).toBeTruthy();
        expect(screen.getByText("Edit budget")).toBeTruthy();
        expect(screen.getByLabelText(/^Next period/)).toBeTruthy();
    });

    test("BR-BUD-02: saving without any limit is refused locally", async () => {
        const calls = mockApi(noBudget());
        await mount();
        await fireEvent.press(await screen.findByText("Set budget"));
        await fireEvent.press(await screen.findByText("Save"));
        expect(
            await screen.findByText("Set at least one limit: overall or for a category."),
        ).toBeTruthy();
        expect(calls.some((c) => c.method === "PUT")).toBe(false);
    });

    test("BR-MON-01: creating sends amounts as strings in the household currency, no If-Match", async () => {
        const calls = mockApi({
            ...noBudget(),
            "PUT /api/v1/budgets/2026-03-01": () => ({ status: 201, body: budget() }),
        });
        await mount();
        await fireEvent.press(await screen.findByText("Set budget"));
        const input = await screen.findByLabelText("Overall limit (EUR)");
        await fireEvent.changeText(input, "500");
        await fireEvent(input, "blur");
        await fireEvent.press(screen.getByText("Save"));
        await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
        const put = calls.find((c) => c.method === "PUT")!;
        expect(await put.json()).toEqual({
            overallLimit: { amount: "500.00", currency: "EUR" },
            categoryLimits: [],
        });
        expect(put.headers.get("If-Match")).toBeNull();
    });

    test("a category limit can be added from the picker and is sent as a string", async () => {
        const calls = mockApi({
            ...noBudget(),
            "PUT /api/v1/budgets/2026-03-01": () => ({ status: 201, body: budget() }),
        });
        await mount();
        await fireEvent.press(await screen.findByText("Set budget"));
        await fireEvent.press(await screen.findByLabelText("Housing"));
        const input = await screen.findByLabelText("Housing limit (EUR)");
        await fireEvent.changeText(input, "800.5");
        await fireEvent(input, "blur");
        await fireEvent.press(screen.getByText("Save"));
        await waitFor(() => expect(calls.some((c) => c.method === "PUT")).toBe(true));
        const put = calls.find((c) => c.method === "PUT")!;
        expect(await put.json()).toEqual({
            categoryLimits: [
                {
                    categoryId: "019a0000-0000-7000-8000-000000000002",
                    limit: { amount: "800.50", currency: "EUR" },
                },
            ],
        });
    });

    test("editing sends the last read version in If-Match; a stale version prompts a reload", async () => {
        const calls = mockApi({
            ...withBudget(budget()),
            "PUT /api/v1/budgets/2026-03-01": () => problem(412, "VERSION_CONFLICT"),
        });
        await mount();
        await fireEvent.press(await screen.findByText("Edit budget"));
        await fireEvent.press(await screen.findByText("Save"));
        expect(
            await screen.findByText(/changed elsewhere\. Reload it before editing again/),
        ).toBeTruthy();
        expect(screen.getByText("Reload budget")).toBeTruthy();
        const put = calls.find((c) => c.method === "PUT")!;
        expect(put.headers.get("If-Match")).toBe('"3"');
    });
});

describe("Budget vs spent per category (MOBILE-008)", () => {
    const HOUSING = "019a0000-0000-7000-8000-000000000002";
    const consumption = (
        consumed: string,
        remaining: string,
        percentage: string,
        status: string,
    ) => ({
        consumed: eur(consumed),
        remaining: eur(remaining),
        percentage,
        status,
        scope: "HOUSEHOLD",
    });

    test("BR-BUD-03/06: each category limit shows spent / limit, server percentage and a written state", async () => {
        mockApi(
            withBudget(
                budget({
                    categoryLimits: [
                        {
                            categoryId: CAT,
                            limit: eur("200.00"),
                            consumption: consumption("50.00", "150.00", "25.0", "ON_TRACK"),
                        },
                        {
                            categoryId: HOUSING,
                            limit: eur("800.00"),
                            consumption: consumption("900.00", "-100.00", "112.5", "EXCEEDED"),
                        },
                    ],
                }),
            ),
        );
        await mount();
        expect(await screen.findByText("Budget vs spent")).toBeTruthy();
        expect(screen.getByText(`${fmt("50.00")} of ${fmt("200.00")}`)).toBeTruthy();
        expect(screen.getByText("25.0 %")).toBeTruthy();
        expect(screen.getByText("On track")).toBeTruthy();
        // the over-budget category: state in words, not only colour
        expect(screen.getByText(`${fmt("900.00")} of ${fmt("800.00")}`)).toBeTruthy();
        expect(screen.getByText("112.5 %")).toBeTruthy();
        expect(screen.getByText("Exceeded")).toBeTruthy();
        const bars = screen.getAllByRole("progressbar");
        expect(bars).toHaveLength(3); // overall + 2 categories
        const exceeded = bars.find((b) => b.props.accessibilityLabel === "Exceeded, 112.5 %");
        expect(exceeded?.props.accessibilityValue.text).toContain("Housing: spent");
    });

    test("WARNING category shows its approaching-limit state", async () => {
        mockApi(
            withBudget(
                budget({
                    categoryLimits: [
                        {
                            categoryId: CAT,
                            limit: eur("100.00"),
                            consumption: consumption("80.00", "20.00", "80.0", "WARNING"),
                        },
                    ],
                }),
            ),
        );
        await mount();
        expect(await screen.findByText("80.0 %")).toBeTruthy();
        expect(screen.getAllByText("Warning").length).toBeGreaterThan(0);
    });

    test("a limit without consumption (older server) still shows its limit only", async () => {
        mockApi(withBudget(budget()));
        await mount();
        expect(await screen.findByText("Groceries")).toBeTruthy();
        expect(screen.getByText(fmt("200.00"))).toBeTruthy();
        expect(screen.queryByTestId("category-limit-row")).toBeNull();
    });

    test("BR-MON-02: very large spent and limit amounts are shown from exact strings", async () => {
        const huge = "90071992547409930.01";
        mockApi(
            withBudget(
                budget({
                    categoryLimits: [
                        {
                            categoryId: CAT,
                            limit: eur(huge),
                            consumption: consumption(huge, "0.00", "100.0", "WARNING"),
                        },
                    ],
                }),
            ),
        );
        await mount();
        expect(await screen.findByText(`${fmt(huge)} of ${fmt(huge)}`)).toBeTruthy();
    });
});
