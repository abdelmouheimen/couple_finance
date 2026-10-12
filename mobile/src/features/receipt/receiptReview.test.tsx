import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { configure, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { type ReactNode } from "react";
import { HouseholdProvider, useHouseholdState } from "@/features/household/HouseholdProvider";
import { renderUi } from "@/shared/ui/testing";
import { buildConfirmRequest, type ReceiptReview } from "./receiptApi";
import { ReceiptReviewContainer } from "./ReceiptReviewContainer";

configure({ asyncUtilTimeout: 15000 });

const mockRouter = { push: jest.fn(), back: jest.fn(), replace: jest.fn(), canGoBack: () => true };
jest.mock("expo-router", () => ({
    useRouter: () => mockRouter,
    useLocalSearchParams: () => ({}),
}));

const NOW = () => new Date("2026-10-12T10:00:00Z");
const ID = "019a1111-0000-7000-8000-000000000001";
const EXPENSE = "019a2222-0000-7000-8000-000000000002";
const ME = "019a3333-0000-7000-8000-000000000003";
const GROCERIES = { id: "c-groceries", type: "SYSTEM", systemCode: "GROCERIES", archived: false };
const OTHER = { id: "c-other", type: "SYSTEM", systemCode: "OTHER", archived: false };

const household = {
    id: "11111111-1111-7111-8111-111111111111",
    name: "Foyer",
    currency: "EUR",
    timezone: "Europe/Paris",
    periodStartDay: 1,
    status: "ACTIVE",
    version: 1,
};

const field = (raw: string, status = "OK", candidates: string[] = []) => ({
    raw,
    status,
    candidates,
});

function review(over: Partial<ReceiptReview> = {}): ReceiptReview {
    return {
        receiptId: ID,
        version: 4,
        state: "READY_FOR_REVIEW",
        analysed: true,
        documentType: "RECEIPT",
        refundProposed: false,
        householdCurrency: "EUR",
        proposedMerchant: "Carrefour",
        proposedDate: "2026-10-04",
        proposedTotal: { amount: "13.78", currency: "EUR" },
        detectedCurrency: "EUR",
        fields: {
            merchant: field("Carrefour"),
            date: field("04/10/2026"),
            total: field("13,78"),
            currency: field("€"),
        },
        unresolvedFields: [],
        lines: [],
        findings: [],
        suggestedCategory: { categoryId: GROCERIES.id, source: "HOUSEHOLD_RULE" },
        duplicates: [],
        ...over,
    };
}

interface Recorded {
    key: string;
    headers: Headers;
    body: unknown;
}
type Handler = () => Response | Promise<Response>;
const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const problem = (status: number, code: string) => json(status, { code, status, title: "x" });

function installApi(handlers: Record<string, Handler | Handler[]>) {
    const calls: Recorded[] = [];
    const queues = new Map<string, Handler[]>();
    const base: Record<string, Handler | Handler[]> = {
        "GET /api/v1/households/me": () => json(200, household),
        "GET /api/v1/me": () => json(200, { id: ME }),
        "GET /api/v1/categories": () => json(200, { items: [GROCERIES, OTHER] }),
        "GET /api/v1/category-suggestions": () =>
            json(200, { categoryId: GROCERIES.id, source: "HOUSEHOLD_RULE" }),
        "GET /api/v1/expenses": () => json(200, { items: [] }),
        "GET /api/v1/receipts": () => json(200, { items: [] }),
        ...handlers,
    };
    jest.spyOn(globalThis, "fetch").mockImplementation((async (input: Request) => {
        const url = new URL(input.url);
        const key = `${input.method} ${url.pathname}`;
        const text = input.method === "GET" ? "" : await input.clone().text();
        calls.push({
            key,
            headers: input.headers,
            body: text === "" ? undefined : JSON.parse(text),
        });
        const entry = base[key];
        if (!entry) throw new Error(`Unexpected request ${key}`);
        if (Array.isArray(entry)) {
            const queue = queues.get(key) ?? [...entry];
            queues.set(key, queue);
            const next = queue.length > 1 ? queue.shift()! : queue[0]!;
            return next();
        }
        return entry();
    }) as unknown as typeof fetch);
    return { of: (key: string) => calls.filter((c) => c.key === key) };
}

function Gate({ children }: { children: ReactNode }) {
    const { state } = useHouseholdState();
    return state.kind === "ready" ? <>{children}</> : null;
}

async function mount() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    await renderUi(
        <QueryClientProvider client={client}>
            <HouseholdProvider now={NOW}>
                <Gate>
                    <ReceiptReviewContainer id={ID} now={NOW} />
                </Gate>
            </HouseholdProvider>
        </QueryClientProvider>,
    );
}

const REVIEW = `GET /api/v1/receipts/${ID}/review`;
const CONFIRM = `POST /api/v1/receipts/${ID}/confirm`;
const ATTACH = `POST /api/v1/receipts/${ID}/attach`;
const link = (state: string) => ({ receiptId: ID, expenseId: EXPENSE, state });

beforeEach(() => jest.clearAllMocks());
afterEach(() => jest.restoreAllMocks());

describe("receipt review and confirmation (DOC-004)", () => {
    test("BR_RCP_10_the_expense_is_created_only_after_the_total_and_date_are_acknowledged", async () => {
        const api = installApi({
            [REVIEW]: () => json(200, review()),
            [CONFIRM]: () => json(201, link("CONFIRMED")),
        });
        await mount();

        expect(await screen.findByTestId("review-notice")).toHaveTextContent(
            /ce sont des propositions/,
        );
        expect(screen.getByLabelText("Montant (EUR)").props.value).toMatch(/13[.,]78/);
        expect(screen.getByLabelText("Commerçant").props.value).toBe("Carrefour");
        await fireEvent.press(screen.getByRole("button", { name: "Créer la dépense" }));

        // Nothing is sent before the explicit acknowledgement of the total and the date.
        expect(await screen.findByText("Confirmer le montant et la date")).toBeTruthy();
        expect(screen.getByText(/13,78/)).toBeTruthy();
        expect(api.of(CONFIRM)).toHaveLength(0);
        await fireEvent.press(screen.getByRole("button", { name: "Je confirme" }));

        await waitFor(() => expect(mockRouter.replace).toHaveBeenCalledWith(`/expense/${EXPENSE}`));
        const [sent] = api.of(CONFIRM);
        expect(sent!.headers.get("Idempotency-Key")).toBeTruthy();
        expect(sent!.body).toEqual({
            version: 4,
            kind: "EXPENSE",
            amount: { amount: "13.78", currency: "EUR" },
            date: "2026-10-04",
            categoryId: GROCERIES.id,
            paidByUserId: ME,
            sharingType: "SHARED",
            merchant: "Carrefour",
            acknowledgedTotal: { amount: "13.78", currency: "EUR" },
            acknowledgedDate: "2026-10-04",
        });
    });

    test("BR_RCP_10_the_member_can_correct_the_proposal_and_their_value_is_what_is_confirmed", async () => {
        const api = installApi({
            [REVIEW]: () => json(200, review()),
            [CONFIRM]: () => json(201, link("CONFIRMED")),
        });
        await mount();

        await fireEvent.changeText(await screen.findByLabelText("Montant (EUR)"), "14.78");
        await fireEvent.press(screen.getByRole("button", { name: "Créer la dépense" }));
        await fireEvent.press(await screen.findByRole("button", { name: "Je confirme" }));

        await waitFor(() => expect(api.of(CONFIRM)).toHaveLength(1));
        const body = api.of(CONFIRM)[0]!.body as Record<string, unknown>;
        expect(body.amount).toEqual({ amount: "14.78", currency: "EUR" });
        expect(body.acknowledgedTotal).toEqual({ amount: "14.78", currency: "EUR" });
    });

    test("BR_RCP_05_an_ambiguous_total_is_never_prefilled_and_must_be_resolved", async () => {
        const api = installApi({
            [REVIEW]: () =>
                json(
                    200,
                    review({
                        proposedTotal: undefined,
                        fields: {
                            ...review().fields,
                            total: field("1.378", "AMBIGUOUS", ["1.378", "1378.00"]),
                        },
                        unresolvedFields: ["total"],
                    }),
                ),
            [CONFIRM]: () => json(201, link("CONFIRMED")),
        });
        await mount();

        const flagged = await screen.findByTestId("review-unresolved");
        expect(flagged).toHaveTextContent(/Montant : plusieurs lectures possibles/);
        expect(flagged).toHaveTextContent(/1\.378 · 1378\.00/);
        expect(screen.getByLabelText("Montant (EUR)").props.value).toBe("");
        await fireEvent.changeText(screen.getByLabelText("Montant (EUR)"), "13.78");
        await fireEvent.press(screen.getByRole("button", { name: "Créer la dépense" }));
        expect(await screen.findByText(/Champs vérifiés : Montant/)).toBeTruthy();
        await fireEvent.press(screen.getByRole("button", { name: "Je confirme" }));

        await waitFor(() => expect(api.of(CONFIRM)).toHaveLength(1));
        expect((api.of(CONFIRM)[0]!.body as Record<string, unknown>).resolutions).toEqual({
            total: "13.78",
        });
    });

    test("BR_RCP_14_a_duplicate_is_a_warning_and_the_receipt_can_be_attached_instead", async () => {
        const api = installApi({
            [REVIEW]: () =>
                json(
                    200,
                    review({
                        duplicates: [
                            { reason: "MATCHING_EXPENSE", expenseId: EXPENSE, attachable: true },
                        ],
                    }),
                ),
            [ATTACH]: () => json(200, link("ATTACHED")),
        });
        await mount();

        expect(await screen.findByTestId("review-duplicate")).toHaveTextContent(/existe déjà/);
        // A warning, not a block: creating the expense is still offered.
        expect(screen.getByRole("button", { name: "Créer la dépense" })).toBeTruthy();
        await fireEvent.press(screen.getByRole("button", { name: "Rattacher à cette dépense" }));
        expect(api.of(ATTACH)).toHaveLength(0);
        const confirm = await screen.findAllByRole("button", { name: "Rattacher à cette dépense" });
        await fireEvent.press(confirm[confirm.length - 1]!);

        await waitFor(() => expect(mockRouter.replace).toHaveBeenCalledWith(`/expense/${EXPENSE}`));
        expect(api.of(ATTACH)[0]!.body).toEqual({ expenseId: EXPENSE, version: 4 });
        expect(api.of(CONFIRM)).toHaveLength(0);
    });

    test("a receipt never analysed is confirmed as a manual entry", async () => {
        const api = installApi({
            [REVIEW]: () =>
                json(
                    200,
                    review({
                        analysed: false,
                        state: "UPLOADED",
                        proposedMerchant: undefined,
                        proposedDate: undefined,
                        proposedTotal: undefined,
                        detectedCurrency: undefined,
                        suggestedCategory: undefined,
                        fields: {},
                    }),
                ),
            [CONFIRM]: () => json(201, link("CONFIRMED")),
        });
        await mount();

        expect(await screen.findByTestId("review-notice")).toHaveTextContent(/n'a pas été analysé/);
        expect(screen.getByLabelText("Montant (EUR)").props.value).toBe("");
        await fireEvent.press(screen.getByRole("button", { name: "Créer la dépense" }));
        expect(api.of(CONFIRM)).toHaveLength(0); // the form refuses an empty amount and category
    });

    test("a receipt that changed is reloaded and the member reviews it again", async () => {
        const api = installApi({
            [REVIEW]: [() => json(200, review()), () => json(200, review({ version: 5 }))],
            [CONFIRM]: () => problem(409, "RECEIPT_VERSION_CONFLICT"),
        });
        await mount();

        await fireEvent.press(await screen.findByRole("button", { name: "Créer la dépense" }));
        await fireEvent.press(await screen.findByRole("button", { name: "Je confirme" }));

        expect(await screen.findByText(/a changé depuis son affichage/)).toBeTruthy();
        await waitFor(() => expect(api.of(REVIEW).length).toBeGreaterThanOrEqual(2));
        expect(mockRouter.replace).not.toHaveBeenCalled();
    });

    test("BR_RCP_15_an_already_confirmed_receipt_is_explained", async () => {
        installApi({
            [REVIEW]: () => json(200, review()),
            [CONFIRM]: () => problem(409, "RECEIPT_ALREADY_CONFIRMED"),
        });
        await mount();

        await fireEvent.press(await screen.findByRole("button", { name: "Créer la dépense" }));
        await fireEvent.press(await screen.findByRole("button", { name: "Je confirme" }));

        expect(await screen.findByText(/déjà rattaché à une dépense/)).toBeTruthy();
    });

    test("the partner's or another household's receipt is not reviewable (404)", async () => {
        installApi({ [REVIEW]: () => problem(404, "RECEIPT_NOT_FOUND") });
        await mount();
        expect(await screen.findByText("Ce ticket est introuvable.")).toBeTruthy();
    });
});

describe("confirmation request (financial accuracy)", () => {
    const values = {
        amount: "13.78",
        categoryId: GROCERIES.id,
        date: "2026-10-04",
        merchant: " Carrefour ",
        note: "",
        sharingType: "PERSONAL" as const,
    };

    test("the amount stays the typed string and is acknowledged unchanged", () => {
        const body = buildConfirmRequest(values, review(), { currency: "EUR", paidByUserId: ME });
        expect(body.amount).toEqual({ amount: "13.78", currency: "EUR" });
        expect(body.acknowledgedTotal).toEqual(body.amount);
        expect(typeof body.amount.amount).toBe("string");
        expect(body.acknowledgedDate).toBe(body.date);
        expect(body.merchant).toBe("Carrefour");
        expect(body.sharingType).toBe("PERSONAL");
        expect(body.resolutions).toBeUndefined();
    });

    test("every flagged field carries the value the member kept", () => {
        const body = buildConfirmRequest(
            values,
            review({ unresolvedFields: ["merchant", "date", "total", "currency"] }),
            { currency: "EUR", paidByUserId: ME },
        );
        expect(body.resolutions).toEqual({
            merchant: "Carrefour",
            date: "2026-10-04",
            total: "13.78",
            currency: "EUR",
        });
    });

    test("a refund receipt is confirmed as a refund", () => {
        expect(
            buildConfirmRequest(values, review({ refundProposed: true }), {
                currency: "EUR",
                paidByUserId: ME,
            }).kind,
        ).toBe("REFUND");
    });
});
