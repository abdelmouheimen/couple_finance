import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { configure, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { type ReactNode } from "react";
import { HouseholdProvider, useHouseholdState } from "@/features/household/HouseholdProvider";
import { QuickAddScreen } from "@/features/expense/QuickAddScreen";
import { renderUi } from "@/shared/ui/testing";
import { ReceiptCapturePanel } from "./ReceiptCapturePanel";
import { ReceiptDetailScreen } from "./ReceiptDetailScreen";
import { ReceiptVaultScreen } from "./ReceiptVaultScreen";
import { filterReceipts, type KindFilter } from "./receiptFormat";
import { type Receipt, receiptFormData } from "./receiptApi";
import * as device from "./receiptDevice";

configure({ asyncUtilTimeout: 15000 });

const mockRouter = { push: jest.fn(), back: jest.fn(), replace: jest.fn(), canGoBack: () => true };
jest.mock("expo-router", () => ({
    useRouter: () => mockRouter,
    useLocalSearchParams: () => ({}),
}));
jest.mock("./receiptDevice", () => ({
    MAX_RECEIPT_PHOTOS: 5,
    captureFromCamera: jest.fn(),
    pickFromGallery: jest.fn(),
    pickPdf: jest.fn(),
    discard: jest.fn(),
    sharePage: jest.fn(),
}));
const mocked = jest.mocked(device);

const NOW = () => new Date("2026-10-12T10:00:00Z");
const ID = "019a1111-0000-7000-8000-000000000001";

const household = {
    id: "11111111-1111-7111-8111-111111111111",
    name: "Foyer",
    currency: "EUR",
    timezone: "Europe/Paris",
    periodStartDay: 1,
    status: "ACTIVE",
    version: 1,
};

function receipt(over: Partial<Receipt> = {}): Receipt {
    return {
        id: ID,
        sourceKind: "IMAGES",
        state: "UPLOADED",
        totalSizeBytes: 1234,
        pages: [
            {
                position: 1,
                contentType: "image/jpeg",
                sizeBytes: 1000,
                widthPx: 600,
                heightPx: 900,
            },
        ],
        createdAt: "2026-10-04T09:00:00Z",
        version: 0,
        ...over,
    };
}

const photo = (n: number) => ({
    uri: `file:///cache/page-${n}.jpg`,
    name: `page-${n}.jpg`,
    mimeType: "image/jpeg" as const,
});

interface Recorded {
    key: string;
    headers: Headers;
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
        "GET /api/v1/me": () => json(200, { id: "me" }),
        "GET /api/v1/categories": () => json(200, { items: [] }),
        ...handlers,
    };
    jest.spyOn(globalThis, "fetch").mockImplementation((async (input: Request) => {
        const url = new URL(input.url);
        const key = `${input.method} ${url.pathname}`;
        calls.push({ key, headers: input.headers });
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
    jest.clearAllMocks();
});
afterEach(() => jest.restoreAllMocks());

describe("receipt capture and upload (DOC-002, BR-RCP-01)", () => {
    test("a scanned page is shown, uploaded with an Idempotency-Key and its local copy deleted", async () => {
        const api = installApi({ "POST /api/v1/receipts": () => json(201, receipt()) });
        mocked.captureFromCamera.mockResolvedValue({ kind: "picked", files: [photo(1)] });
        const onUploaded = jest.fn();
        await mount(
            <ReceiptCapturePanel mode="SCAN" onUploaded={onUploaded} onUseManual={jest.fn()} />,
        );

        await fireEvent.press(await screen.findByRole("button", { name: "Prendre la photo" }));
        expect(await screen.findByLabelText("Page 1 sélectionnée")).toBeTruthy();
        await fireEvent.press(screen.getByRole("button", { name: "Envoyer le ticket" }));

        await waitFor(() => expect(onUploaded).toHaveBeenCalledWith(receipt()));
        const post = api.of("POST /api/v1/receipts")[0]!;
        expect(post.headers.get("Idempotency-Key")).toBeTruthy();
        expect(mocked.discard).toHaveBeenCalledWith([photo(1).uri]);
    });

    test("BR_EXP_13_a_retry_after_a_network_failure_reuses_the_same_idempotency_key", async () => {
        const api = installApi({
            "POST /api/v1/receipts": [
                () => {
                    throw new TypeError("Network request failed");
                },
                () => json(201, receipt()),
            ],
        });
        mocked.captureFromCamera.mockResolvedValue({ kind: "picked", files: [photo(1)] });
        const onUploaded = jest.fn();
        await mount(
            <ReceiptCapturePanel mode="SCAN" onUploaded={onUploaded} onUseManual={jest.fn()} />,
        );
        await fireEvent.press(await screen.findByRole("button", { name: "Prendre la photo" }));
        await screen.findByLabelText("Page 1 sélectionnée");

        await fireEvent.press(screen.getByRole("button", { name: "Envoyer le ticket" }));
        expect(await screen.findByText(/Serveur injoignable/)).toBeTruthy();
        await fireEvent.press(screen.getByRole("button", { name: "Envoyer le ticket" }));

        await waitFor(() => expect(onUploaded).toHaveBeenCalled());
        const [first, retry] = api.of("POST /api/v1/receipts");
        expect(retry!.headers.get("Idempotency-Key")).toBe(first!.headers.get("Idempotency-Key"));
    });

    test.each([
        ["RECEIPT_FORMAT_UNSUPPORTED", 415, /Seuls les photos/],
        ["RECEIPT_FILE_REJECTED", 422, /refusé par le contrôle de sécurité/],
        ["RECEIPT_SCAN_UNAVAILABLE", 503, /momentanément indisponible/],
        ["RECEIPT_TOO_LARGE", 413, /trop volumineux/],
    ])("server problem %s is shown as a friendly message", async (code, status, text) => {
        installApi({ "POST /api/v1/receipts": () => problem(status, code) });
        mocked.captureFromCamera.mockResolvedValue({ kind: "picked", files: [photo(1)] });
        await mount(
            <ReceiptCapturePanel mode="SCAN" onUploaded={jest.fn()} onUseManual={jest.fn()} />,
        );
        await fireEvent.press(await screen.findByRole("button", { name: "Prendre la photo" }));
        await screen.findByLabelText("Page 1 sélectionnée");
        await fireEvent.press(screen.getByRole("button", { name: "Envoyer le ticket" }));
        expect(await screen.findByText(text)).toBeTruthy();
        expect(mocked.discard).not.toHaveBeenCalledWith([photo(1).uri]);
    });

    test("a denied camera permission is explained", async () => {
        installApi({});
        mocked.captureFromCamera.mockResolvedValue({ kind: "denied" });
        await mount(
            <ReceiptCapturePanel mode="SCAN" onUploaded={jest.fn()} onUseManual={jest.fn()} />,
        );
        await fireEvent.press(await screen.findByRole("button", { name: "Prendre la photo" }));
        expect(await screen.findByText(/accès à l'appareil photo est refusé/)).toBeTruthy();
    });

    test("BR_RCP_01_at_most_five_photos_then_capture_is_disabled", async () => {
        installApi({});
        mocked.pickFromGallery.mockResolvedValue({
            kind: "picked",
            files: [photo(1), photo(2), photo(3), photo(4), photo(5)],
        });
        await mount(
            <ReceiptCapturePanel mode="SCAN" onUploaded={jest.fn()} onUseManual={jest.fn()} />,
        );
        await fireEvent.press(
            await screen.findByRole("button", { name: "Choisir dans la galerie" }),
        );
        expect(await screen.findByLabelText("Page 5 sélectionnée")).toBeTruthy();
        expect(mocked.pickFromGallery).toHaveBeenCalledWith(5);
        expect(screen.getByRole("button", { name: "Ajouter une page" })).toBeDisabled();
        expect(screen.getByRole("button", { name: "Envoyer le ticket (5 pages)" })).toBeTruthy();

        await fireEvent.press(screen.getByRole("button", { name: "Retirer la page 2" }));
        expect(mocked.discard).toHaveBeenCalledWith([photo(2).uri]);
        expect(screen.queryByLabelText("Page 5 sélectionnée")).toBeNull();
    });

    test("import mode selects one PDF and restart discards the selection", async () => {
        installApi({});
        const pdf = {
            uri: "file:///cache/r.pdf",
            name: "receipt.pdf",
            mimeType: "application/pdf" as const,
        };
        mocked.pickPdf.mockResolvedValue({ kind: "picked", files: [pdf] });
        await mount(
            <ReceiptCapturePanel mode="IMPORT" onUploaded={jest.fn()} onUseManual={jest.fn()} />,
        );
        await fireEvent.press(await screen.findByRole("button", { name: "Choisir un PDF" }));
        expect(await screen.findByLabelText("PDF sélectionné")).toBeTruthy();
        expect(screen.getByRole("button", { name: "Choisir des photos" })).toBeDisabled();
        await fireEvent.press(screen.getByRole("button", { name: "Recommencer" }));
        expect(mocked.discard).toHaveBeenCalledWith([pdf.uri]);
    });

    test("after an upload from Ajouter une dépense the user lands on the stored receipt", async () => {
        installApi({ "POST /api/v1/receipts": () => json(201, receipt()) });
        mocked.captureFromCamera.mockResolvedValue({ kind: "picked", files: [photo(1)] });
        await mount(<QuickAddScreen now={NOW} />);
        await fireEvent.press(await screen.findByRole("radio", { name: "Scanner" }));
        await fireEvent.press(await screen.findByRole("button", { name: "Prendre la photo" }));
        await screen.findByLabelText("Page 1 sélectionnée");
        await fireEvent.press(screen.getByRole("button", { name: "Envoyer le ticket" }));
        await waitFor(() => expect(mockRouter.replace).toHaveBeenCalledWith(`/receipts/${ID}`));
        expect(await screen.findByText(/saisissez la dépense manuellement/)).toBeTruthy();
    });
});

describe("receipt vault (DOC-002, BR-RCP-16)", () => {
    test("lists receipts with date, kind, pages and state, filters by kind and opens one", async () => {
        const pdf = receipt({ id: "pdf-1", sourceKind: "PDF", createdAt: "2026-10-02T09:00:00Z" });
        installApi({ "GET /api/v1/receipts": () => json(200, { items: [receipt(), pdf] }) });
        await mount(<ReceiptVaultScreen />);

        const row = await screen.findByTestId(`receipt-row-${ID}`);
        expect(row.props.accessibilityLabel).toMatch(/Photos, 1 page, Enregistré/);
        expect(screen.getByTestId("receipt-row-pdf-1")).toBeTruthy();

        await fireEvent.press(screen.getByRole("radio", { name: "PDF" }));
        expect(screen.queryByTestId(`receipt-row-${ID}`)).toBeNull();
        await fireEvent.press(screen.getByTestId("receipt-row-pdf-1"));
        expect(mockRouter.push).toHaveBeenCalledWith("/receipts/pdf-1");
    });

    test("empty and error states", async () => {
        installApi({ "GET /api/v1/receipts": () => json(200, { items: [] }) });
        await mount(<ReceiptVaultScreen />);
        expect(await screen.findByText("Aucun ticket")).toBeTruthy();
        const add = screen.getAllByRole("button", { name: "Ajouter un ticket" });
        expect(add).toHaveLength(2); // header action and empty-state action
        await fireEvent.press(add[1]!);
        expect(mockRouter.push).toHaveBeenCalledWith("/add-expense");
    });

    test("a failing list can be retried", async () => {
        installApi({ "GET /api/v1/receipts": () => problem(500, "INTERNAL_ERROR") });
        await mount(<ReceiptVaultScreen />);
        expect(await screen.findByRole("button", { name: "Réessayer" })).toBeTruthy();
    });
});

describe("receipt detail (DOC-002)", () => {
    const pageUrl = {
        url: "https://storage.example/receipts/abc?X-Amz-Signature=sig",
        expiresAt: "2026-10-12T10:05:00Z",
        contentType: "image/jpeg",
    };

    test("previews the page through its short-lived URL and shares a fresh one", async () => {
        const api = installApi({
            [`GET /api/v1/receipts/${ID}`]: () => json(200, receipt()),
            [`GET /api/v1/receipts/${ID}/pages/1/url`]: () => json(200, pageUrl),
        });
        mocked.sharePage.mockResolvedValue(undefined);
        await mount(<ReceiptDetailScreen id={ID} />);

        const image = await screen.findByTestId("receipt-page-image");
        expect(image.props.source).toEqual({ uri: pageUrl.url });
        await fireEvent.press(screen.getByRole("button", { name: "Télécharger / partager" }));
        await waitFor(() => expect(mocked.sharePage).toHaveBeenCalledWith(pageUrl.url, ID, 1));
        expect(api.of(`GET /api/v1/receipts/${ID}/pages/1/url`).length).toBeGreaterThanOrEqual(2);
    });

    test("pages of a multi-page receipt are browsable", async () => {
        installApi({
            [`GET /api/v1/receipts/${ID}`]: () =>
                json(
                    200,
                    receipt({
                        pages: [...receipt().pages, { ...receipt().pages[0]!, position: 2 }],
                    }),
                ),
            [`GET /api/v1/receipts/${ID}/pages/1/url`]: () => json(200, pageUrl),
            [`GET /api/v1/receipts/${ID}/pages/2/url`]: () =>
                json(200, { ...pageUrl, url: pageUrl.url + "2" }),
        });
        await mount(<ReceiptDetailScreen id={ID} />);
        expect(await screen.findByText("Page 1 sur 2")).toBeTruthy();
        expect(screen.getByRole("button", { name: "Page précédente" })).toBeDisabled();
        await fireEvent.press(screen.getByRole("button", { name: "Page suivante" }));
        expect(await screen.findByText("Page 2 sur 2")).toBeTruthy();
        await waitFor(() =>
            expect(screen.getByTestId("receipt-page-image").props.source).toEqual({
                uri: pageUrl.url + "2",
            }),
        );
    });

    test("deletion needs confirmation, then goes back", async () => {
        const api = installApi({
            [`GET /api/v1/receipts/${ID}`]: () => json(200, receipt()),
            [`GET /api/v1/receipts/${ID}/pages/1/url`]: () => json(200, pageUrl),
            [`DELETE /api/v1/receipts/${ID}`]: () => new Response(null, { status: 204 }),
            "GET /api/v1/receipts": () => json(200, { items: [] }),
        });
        await mount(<ReceiptDetailScreen id={ID} />);
        await fireEvent.press(await screen.findByRole("button", { name: "Supprimer le ticket" }));
        expect(api.of(`DELETE /api/v1/receipts/${ID}`)).toHaveLength(0);
        const confirm = await screen.findAllByRole("button", { name: "Supprimer le ticket" });
        await fireEvent.press(confirm[confirm.length - 1]!);
        await waitFor(() => expect(mockRouter.back).toHaveBeenCalled());
        expect(api.of(`DELETE /api/v1/receipts/${ID}`)).toHaveLength(1);
    });

    test("another household's or the partner's receipt is not found (404 non-disclosure)", async () => {
        installApi({ [`GET /api/v1/receipts/${ID}`]: () => problem(404, "RECEIPT_NOT_FOUND") });
        await mount(<ReceiptDetailScreen id={ID} />);
        expect(await screen.findByText("Ce ticket est introuvable.")).toBeTruthy();
    });
});

describe("pure helpers", () => {
    test("the multipart body carries one files part per page, in order", () => {
        const body = receiptFormData([photo(1), photo(2)]);
        expect(body.getAll("files")).toHaveLength(2);
    });

    test.each<[KindFilter, number]>([
        ["ALL", 2],
        ["IMAGES", 1],
        ["PDF", 1],
    ])("filter %s keeps %i receipts", (kind, count) => {
        expect(
            filterReceipts([receipt(), receipt({ id: "p", sourceKind: "PDF" })], kind),
        ).toHaveLength(count);
    });
});
