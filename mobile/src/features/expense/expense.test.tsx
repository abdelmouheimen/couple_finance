import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { configure, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { type ReactNode } from "react";
import { HouseholdProvider, useHouseholdState } from "@/features/household/HouseholdProvider";
import { renderUi } from "@/shared/ui/testing";
import { CategoriesScreen } from "./CategoriesScreen";
import { EditExpenseScreen } from "./EditExpenseScreen";
import { ExpenseDetailScreen } from "./ExpenseDetailScreen";
import { ExpenseListScreen } from "./ExpenseListScreen";
import { QuickAddScreen } from "./QuickAddScreen";

// Cold module/Intl start-up in CI can exceed the 1 s default.
configure({ asyncUtilTimeout: 15000 });

const mockRouter = {
    push: jest.fn(),
    back: jest.fn(),
    replace: jest.fn(),
    canGoBack: () => true,
};
jest.mock("expo-router", () => ({ useRouter: () => mockRouter }));

const NOW = () => new Date("2026-03-15T10:00:00Z");
const ME = "00000000-0000-7000-8000-00000000000a";
const PARTNER = "00000000-0000-7000-8000-00000000000b";

const household = (status = "ACTIVE") => ({
    id: "11111111-1111-7111-8111-111111111111",
    name: "Home",
    currency: "EUR",
    timezone: "Europe/Paris",
    periodStartDay: 1,
    status,
    version: 1,
});

const category = (id: string, systemCode: string | null, name?: string, extra = {}) => ({
    id,
    type: systemCode ? "SYSTEM" : "CUSTOM",
    ...(systemCode ? { systemCode } : { name }),
    archived: false,
    color: "#000000",
    icon: "tag",
    sortOrder: 1,
    version: 1,
    ...extra,
});
const GROCERIES = category("c-groc", "GROCERIES");
const PETS = category("c-pets", null, "Pets");

const money = (amount: string) => ({ amount, currency: "EUR" });

function expense(over: Record<string, unknown> = {}) {
    return {
        id: "e-1",
        amount: money("12.50"),
        createdAt: "2026-03-15T10:00:00Z",
        createdBy: ME,
        date: "2026-03-15",
        items: [{ id: "i-1", categoryId: GROCERIES.id, amount: money("12.50"), position: 0 }],
        kind: "EXPENSE",
        merchant: "Bakery",
        paidByUserId: ME,
        sharingType: "SHARED",
        source: "MANUAL",
        version: 3,
        ...over,
    };
}

const page = (items: unknown[], extra: Record<string, unknown> = {}, scope = "HOUSEHOLD") => ({
    items,
    totals: { count: items.length, net: money("42.00"), scope },
    ...extra,
});

interface Call {
    key: string;
    url: URL;
    headers: Headers;
    body: unknown;
}
type Handler = (call: Call) => Response | Promise<Response>;

const json = (status: number, body: unknown, headers: Record<string, string> = {}) =>
    new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json", ...headers },
    });
const problem = (status: number, code: string) => json(status, { code, status, title: "x" });

/** Routes `METHOD /path` to handlers; unknown routes fail the test loudly. */
function installApi(handlers: Record<string, Handler>) {
    const calls: Call[] = [];
    const base: Record<string, Handler> = {
        "GET /api/v1/households/me": () => json(200, household()),
        "GET /api/v1/me": () => json(200, { id: ME }),
        "GET /api/v1/categories": () => json(200, { items: [GROCERIES, PETS] }),
        ...handlers,
    };
    jest.spyOn(globalThis, "fetch").mockImplementation((async (input: Request) => {
        const url = new URL(input.url);
        const key = `${input.method} ${url.pathname}`;
        const text = await input.clone().text();
        const call: Call = {
            key,
            url,
            headers: input.headers,
            body: text ? (JSON.parse(text) as unknown) : undefined,
        };
        calls.push(call);
        const handler = base[key];
        if (!handler) throw new Error(`Unexpected request ${key}`);
        return handler(call);
    }) as unknown as typeof fetch);
    return { calls, of: (key: string) => calls.filter((c) => c.key === key) };
}

function Gate({ children }: { children: ReactNode }) {
    const { state } = useHouseholdState();
    return state.kind === "ready" ? <>{children}</> : null;
}

async function mount(ui: ReactNode) {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    await renderUi(
        <QueryClientProvider client={client}>
            <HouseholdProvider now={NOW}>
                <Gate>{ui}</Gate>
            </HouseholdProvider>
        </QueryClientProvider>,
    );
}

beforeEach(() => {
    Object.values(mockRouter).forEach((f) => {
        if (jest.isMockFunction(f)) f.mockClear();
    });
});
afterEach(() => jest.restoreAllMocks());

const pressButton = (name: string | RegExp) =>
    fireEvent.press(screen.getByRole("button", { name }));

describe("quick add", () => {
    test("BR_EXP_01_common_shared_expense_is_saved_in_three_interactions_with_defaults", async () => {
        const api = installApi({
            "POST /api/v1/expenses": () => json(201, expense()),
            "GET /api/v1/expenses": () => json(200, page([])),
        });
        await mount(<QuickAddScreen now={NOW} />);
        const amount = await screen.findByLabelText("Amount (EUR)");
        await fireEvent.changeText(amount, "12.50"); // 1: amount
        await fireEvent.press(await screen.findByRole("radio", { name: "Groceries" })); // 2: category
        await pressButton("Save"); // 3: save
        await waitFor(() => expect(api.of("POST /api/v1/expenses")).toHaveLength(1));
        const call = api.of("POST /api/v1/expenses")[0]!;
        expect(call.body).toMatchObject({
            kind: "EXPENSE",
            amount: { amount: "12.50", currency: "EUR" },
            date: "2026-03-15",
            paidByUserId: ME,
            sharingType: "SHARED",
            items: [{ amount: { amount: "12.50", currency: "EUR" }, categoryId: GROCERIES.id }],
        });
        expect(call.headers.get("Idempotency-Key")).toBeTruthy();
        await waitFor(() => expect(mockRouter.back).toHaveBeenCalled());
        expect(await screen.findByText("Expense saved")).toBeTruthy();
    });

    test("BR_EXP_13_double_tap_sends_one_request", async () => {
        let release: () => void = () => {};
        const gate = new Promise<void>((r) => (release = r));
        const api = installApi({
            "POST /api/v1/expenses": async () => {
                await gate;
                return json(201, expense());
            },
            "GET /api/v1/expenses": () => json(200, page([])),
        });
        await mount(<QuickAddScreen now={NOW} />);
        await fireEvent.changeText(await screen.findByLabelText("Amount (EUR)"), "5.00");
        await fireEvent.press(await screen.findByRole("radio", { name: "Groceries" }));
        const save = screen.getByRole("button", { name: "Save" });
        await fireEvent.press(save);
        await fireEvent.press(save);
        release();
        await waitFor(() => expect(mockRouter.back).toHaveBeenCalled());
        expect(api.of("POST /api/v1/expenses")).toHaveLength(1);
    });

    test("BR_EXP_13_a_network_retry_reuses_the_same_idempotency_key", async () => {
        let attempt = 0;
        const api = installApi({
            "POST /api/v1/expenses": () => {
                attempt += 1;
                if (attempt === 1) throw new TypeError("Network request failed");
                return json(201, expense());
            },
            "GET /api/v1/expenses": () => json(200, page([])),
        });
        await mount(<QuickAddScreen now={NOW} />);
        await fireEvent.changeText(await screen.findByLabelText("Amount (EUR)"), "5.00");
        await fireEvent.press(await screen.findByRole("radio", { name: "Groceries" }));
        await pressButton("Save");
        expect(await screen.findByText(/Can't reach the server/)).toBeTruthy();
        await pressButton("Save");
        await waitFor(() => expect(api.of("POST /api/v1/expenses")).toHaveLength(2));
        const [first, second] = api.of("POST /api/v1/expenses");
        expect(second!.headers.get("Idempotency-Key")).toBe(first!.headers.get("Idempotency-Key"));
    });

    test("client validation blocks an empty amount without a request", async () => {
        const api = installApi({});
        await mount(<QuickAddScreen now={NOW} />);
        await screen.findByLabelText("Amount (EUR)");
        await fireEvent.press(await screen.findByRole("radio", { name: "Groceries" }));
        await pressButton("Save");
        expect(await screen.findByText("Enter a valid amount.")).toBeTruthy();
        expect(api.of("POST /api/v1/expenses")).toHaveLength(0);
    });

    test("server problem codes map to friendly field messages", async () => {
        installApi({
            "POST /api/v1/expenses": () => problem(400, "AMOUNT_EXCEEDS_MAXIMUM"),
        });
        await mount(<QuickAddScreen now={NOW} />);
        await fireEvent.changeText(await screen.findByLabelText("Amount (EUR)"), "5.00");
        await fireEvent.press(await screen.findByRole("radio", { name: "Groceries" }));
        await pressButton("Save");
        expect(await screen.findByText("This amount is too large.")).toBeTruthy();
    });

    test("BR_CAT_04_a_suggestion_is_shown_after_merchant_entry_and_applied_only_on_tap", async () => {
        installApi({
            "GET /api/v1/category-suggestions": () =>
                json(200, { categoryId: PETS.id, source: "USER_RULE" }),
        });
        await mount(<QuickAddScreen now={NOW} />);
        await screen.findByLabelText("Amount (EUR)");
        await pressButton("More");
        await fireEvent.changeText(screen.getByLabelText("Merchant"), "Vet");
        const chip = await screen.findByRole("button", { name: "Suggested: Pets (your rule)" });
        expect(screen.getByRole("radio", { name: "Pets" }).props.accessibilityState.checked).toBe(
            false,
        );
        await fireEvent.press(chip);
        expect(screen.getByRole("radio", { name: "Pets" }).props.accessibilityState.checked).toBe(
            true,
        );
    });

    test("BR_CAT_03_archived_categories_are_not_offered_for_new_expenses", async () => {
        installApi({
            "GET /api/v1/categories": () =>
                json(200, { items: [GROCERIES, { ...PETS, archived: true }] }),
        });
        await mount(<QuickAddScreen now={NOW} />);
        await screen.findByRole("radio", { name: "Groceries" });
        expect(screen.queryByRole("radio", { name: "Pets" })).toBeNull();
    });

    test("BR_HH_10_a_dissolved_household_cannot_add", async () => {
        installApi({ "GET /api/v1/households/me": () => json(200, household("DISSOLVED")) });
        await mount(<QuickAddScreen now={NOW} />);
        expect(await screen.findByText("This household is read-only.")).toBeTruthy();
        expect(screen.queryByRole("button", { name: "Save" })).toBeNull();
    });
});

describe("expense list", () => {
    test("BR_SCP_03_paginates_by_cursor_and_shows_the_api_total", async () => {
        const second = expense({ id: "e-2", merchant: "Market", date: "2026-03-14" });
        const api = installApi({
            "GET /api/v1/expenses": ({ url }) =>
                url.searchParams.get("cursor") === "next-1"
                    ? json(200, page([second]))
                    : json(200, page([expense()], { nextCursor: "next-1" })),
        });
        await mount(<ExpenseListScreen />);
        expect(await screen.findByText(/Bakery/)).toBeTruthy();
        expect(screen.getByText(/42[.,]00/)).toBeTruthy(); // total from the API, not summed on device
        const first = api.of("GET /api/v1/expenses")[0]!;
        expect(first.url.searchParams.get("scope")).toBe("HOUSEHOLD");
        expect(first.url.searchParams.get("dateFrom")).toBe("2026-03-01");
        expect(first.url.searchParams.get("dateTo")).toBe("2026-03-31");
        expect(Number(first.url.searchParams.get("limit"))).toBeLessThanOrEqual(100);
        fireEvent(screen.getByTestId("expense-list"), "endReached");
        expect(await screen.findByText(/Market/)).toBeTruthy();
        expect(api.of("GET /api/v1/expenses")).toHaveLength(2);
    });

    test("BR_EXP_07_personal_expenses_appear_with_a_badge_only_in_the_personal_view", async () => {
        const mine = expense({ id: "e-p", merchant: "Secret", sharingType: "PERSONAL" });
        const api = installApi({
            "GET /api/v1/expenses": ({ url }) =>
                url.searchParams.get("scope") === "PERSONAL"
                    ? json(200, page([mine], {}, "PERSONAL"))
                    : json(200, page([expense()])),
        });
        await mount(<ExpenseListScreen />);
        await screen.findByText(/Bakery/);
        expect(screen.queryByText("Secret")).toBeNull();
        await fireEvent.press(screen.getByRole("radio", { name: "Personal" }));
        expect(await screen.findByText("Secret")).toBeTruthy();
        expect(screen.getAllByText("Personal").length).toBeGreaterThan(1); // chip + badge
        expect(api.calls.at(-1)!.url.searchParams.get("scope")).toBe("PERSONAL");
        expect(screen.queryByText("Bakery")).toBeNull();
    });

    test("rows expose merchant, amount, date and scope as one label", async () => {
        installApi({ "GET /api/v1/expenses": () => json(200, page([expense()])) });
        await mount(<ExpenseListScreen />);
        const row = await screen.findByTestId("expense-row-e-1");
        expect(row.props.accessibilityLabel).toMatch(/Bakery, 12 euros 50, .*, Shared/);
    });

    test("empty state offers to add the first expense", async () => {
        installApi({ "GET /api/v1/expenses": () => json(200, page([])) });
        await mount(<ExpenseListScreen />);
        expect(await screen.findByText("No expenses yet")).toBeTruthy();
        await pressButton("Add your first expense");
        expect(mockRouter.push).toHaveBeenCalledWith("/add-expense");
    });

    test("filtered-empty state offers Clear filters", async () => {
        installApi({ "GET /api/v1/expenses": () => json(200, page([])) });
        await mount(<ExpenseListScreen />);
        await screen.findByText("No expenses yet");
        await fireEvent.press(screen.getByRole("radio", { name: "All time" }));
        expect(await screen.findByText("No matching expenses")).toBeTruthy();
        await pressButton("Clear filters");
        expect(await screen.findByText("No expenses yet")).toBeTruthy();
    });

    test("list errors show a retryable error state", async () => {
        installApi({ "GET /api/v1/expenses": () => problem(500, "INTERNAL_ERROR") });
        await mount(<ExpenseListScreen />);
        expect(await screen.findByRole("button", { name: "Retry" })).toBeTruthy();
    });
});

describe("expense detail", () => {
    test("another household's or a partner-personal id shows not found (404 non-disclosure)", async () => {
        installApi({ "GET /api/v1/expenses/e-x": () => problem(404, "EXPENSE_NOT_FOUND") });
        await mount(<ExpenseDetailScreen id="e-x" />);
        expect(await screen.findByText("Expense not found")).toBeTruthy();
    });

    test("BR_EXP_11_delete_needs_confirmation_and_offers_restore", async () => {
        const api = installApi({
            "GET /api/v1/expenses/e-1": () => json(200, expense()),
            "GET /api/v1/expenses/e-1/audit": () => json(200, { items: [] }),
            "DELETE /api/v1/expenses/e-1": () => new Response(null, { status: 204 }),
            "POST /api/v1/expenses/e-1/restore": () => json(200, expense()),
        });
        await mount(<ExpenseDetailScreen id="e-1" />);
        await screen.findByText("Items");
        await pressButton("Delete");
        expect(api.of("DELETE /api/v1/expenses/e-1")).toHaveLength(0); // nothing before confirmation
        await fireEvent.press(screen.getAllByRole("button", { name: "Delete" }).at(-1)!);
        await waitFor(() => expect(api.of("DELETE /api/v1/expenses/e-1")).toHaveLength(1));
        await fireEvent.press(await screen.findByRole("button", { name: "Restore" }));
        await waitFor(() => expect(api.of("POST /api/v1/expenses/e-1/restore")).toHaveLength(1));
    });

    test("BR_EXP_03_a_server_refusal_to_delete_is_shown_clearly", async () => {
        installApi({
            "GET /api/v1/expenses/e-1": () => json(200, expense()),
            "GET /api/v1/expenses/e-1/audit": () => json(200, { items: [] }),
            "DELETE /api/v1/expenses/e-1": () => problem(409, "EXPENSE_HAS_LIVE_REFUNDS"),
        });
        await mount(<ExpenseDetailScreen id="e-1" />);
        await screen.findByText("Items");
        await pressButton("Delete");
        await fireEvent.press(screen.getAllByRole("button", { name: "Delete" }).at(-1)!);
        expect(
            await screen.findByText("This expense has refunds. Delete the refunds first."),
        ).toBeTruthy();
    });

    test("BR_HH_10_a_dissolved_household_hides_edit_and_delete", async () => {
        installApi({
            "GET /api/v1/households/me": () => json(200, household("DISSOLVED")),
            "GET /api/v1/expenses/e-1": () => json(200, expense()),
            "GET /api/v1/expenses/e-1/audit": () => json(200, { items: [] }),
        });
        await mount(<ExpenseDetailScreen id="e-1" />);
        await screen.findByText("Items");
        expect(screen.queryByRole("button", { name: "Edit" })).toBeNull();
        expect(screen.queryByRole("button", { name: "Delete" })).toBeNull();
    });
});

describe("edit expense", () => {
    test("BR_EXP_12_a_412_prompts_a_reload_and_never_overwrites", async () => {
        let gets = 0;
        const api = installApi({
            "GET /api/v1/expenses/e-1": () => {
                gets += 1;
                return json(200, expense({ version: gets === 1 ? 3 : 4 }));
            },
            "PUT /api/v1/expenses/e-1": () => problem(412, "VERSION_CONFLICT"),
        });
        await mount(<EditExpenseScreen id="e-1" />);
        await fireEvent.changeText(await screen.findByLabelText("Merchant"), "Bakery 2");
        await pressButton("Save changes");
        const put = await waitFor(() => {
            const calls = api.of("PUT /api/v1/expenses/e-1");
            expect(calls).toHaveLength(1);
            return calls[0]!;
        });
        expect(put.headers.get("If-Match")).toBe('"3"');
        expect(
            await screen.findByText(
                "This was changed elsewhere. Reload it before editing again; nothing was overwritten.",
            ),
        ).toBeTruthy();
        await pressButton("Reload");
        await waitFor(() => expect(gets).toBe(2));
        expect(await screen.findByLabelText("Merchant")).toBeTruthy(); // form is back with fresh data
        expect(api.of("PUT /api/v1/expenses/e-1")).toHaveLength(1); // nothing re-sent silently
    });

    test("a successful edit sends the string amount with If-Match", async () => {
        const api = installApi({
            "GET /api/v1/expenses/e-1": () => json(200, expense()),
            "PUT /api/v1/expenses/e-1": () => json(200, expense({ version: 4 })),
        });
        await mount(<EditExpenseScreen id="e-1" />);
        await fireEvent.changeText(await screen.findByLabelText("Amount (EUR)"), "13.00");
        await pressButton("Save changes");
        await waitFor(() => expect(api.of("PUT /api/v1/expenses/e-1")).toHaveLength(1));
        expect(api.of("PUT /api/v1/expenses/e-1")[0]!.body).toMatchObject({
            amount: { amount: "13.00", currency: "EUR" },
            paidByUserId: ME,
        });
    });

    test("BR_EXP_08_a_multi_item_expense_shows_its_items_read_only", async () => {
        const multi = expense({
            amount: money("30.00"),
            items: [
                { id: "i-1", categoryId: GROCERIES.id, amount: money("10.00"), position: 0 },
                { id: "i-2", categoryId: PETS.id, amount: money("20.00"), position: 1 },
            ],
        });
        installApi({ "GET /api/v1/expenses/e-1": () => json(200, multi) });
        await mount(<EditExpenseScreen id="e-1" />);
        expect(await screen.findByText(/several items/)).toBeTruthy();
        expect(screen.queryByLabelText("Amount (EUR)")).toBeNull();
    });

    test("BR_EXP_09_the_partner_cannot_switch_sharing", async () => {
        installApi({
            "GET /api/v1/expenses/e-1": () => json(200, expense({ paidByUserId: PARTNER })),
        });
        await mount(<EditExpenseScreen id="e-1" />);
        expect(await screen.findByText("Only the payer can change this.")).toBeTruthy();
        expect(screen.queryByRole("radio", { name: "Personal" })).toBeNull();
    });
});

describe("category management", () => {
    test("BR_CAT_03_system_categories_are_read_only_and_custom_ones_can_be_archived", async () => {
        const api = installApi({
            "PATCH /api/v1/categories/c-pets": () => json(200, { ...PETS, archived: true }),
        });
        await mount(<CategoriesScreen />);
        await screen.findByText("Pets");
        expect(screen.queryByRole("button", { name: /Rename Groceries/ })).toBeNull();
        await pressButton("Archive Pets");
        await waitFor(() => expect(api.of("PATCH /api/v1/categories/c-pets")).toHaveLength(1));
        const patch = api.of("PATCH /api/v1/categories/c-pets")[0]!;
        expect(patch.headers.get("If-Match")).toBe('"1"');
        expect(patch.body).toEqual({ archived: true });
    });

    test("BR_CAT_02_adding_a_category_maps_a_duplicate_name_error", async () => {
        installApi({
            "POST /api/v1/categories": () => problem(409, "CATEGORY_NAME_ALREADY_EXISTS"),
        });
        await mount(<CategoriesScreen />);
        await fireEvent.changeText(await screen.findByLabelText("New category name"), "Pets");
        await pressButton("Add category");
        expect(await screen.findByText("A category with this name already exists.")).toBeTruthy();
    });
});
