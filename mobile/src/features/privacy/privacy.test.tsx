import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { configure, fireEvent, screen, waitFor } from "@testing-library/react-native";
import { renderUi } from "@/shared/ui/testing";
import { isGranted } from "./aiConsentApi";
import { PrivacyScreen } from "./PrivacyScreen";

configure({ asyncUtilTimeout: 15000 });

const mockRouter = { push: jest.fn(), back: jest.fn(), replace: jest.fn(), canGoBack: () => true };
jest.mock("expo-router", () => ({ useRouter: () => mockRouter }));

const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

const consents = (granted: boolean) => ({
    items: [
        { purpose: "RECEIPT_ANALYSIS", granted },
        { purpose: "INSIGHTS_ASSISTANT", granted: false },
        { purpose: "QUALITY_DATASET", granted: false },
    ],
});

interface Recorded {
    key: string;
    body: unknown;
}

function installApi(initiallyGranted: boolean, put?: () => Response) {
    const calls: Recorded[] = [];
    let granted = initiallyGranted;
    jest.spyOn(globalThis, "fetch").mockImplementation((async (input: Request) => {
        const key = `${input.method} ${new URL(input.url).pathname}`;
        if (key === "GET /api/v1/me/ai-consents") {
            calls.push({ key, body: undefined });
            return json(200, consents(granted));
        }
        if (key === "PUT /api/v1/me/ai-consents/RECEIPT_ANALYSIS") {
            const body = (await input.json()) as { granted: boolean };
            calls.push({ key, body });
            if (put) return put();
            granted = body.granted;
            return json(200, { purpose: "RECEIPT_ANALYSIS", granted });
        }
        throw new Error(`Unexpected request ${key}`);
    }) as unknown as typeof fetch);
    return { puts: () => calls.filter((c) => c.key.startsWith("PUT")) };
}

async function mount() {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    await renderUi(
        <QueryClientProvider client={client}>
            <PrivacyScreen />
        </QueryClientProvider>,
    );
}

beforeEach(() => jest.clearAllMocks());
afterEach(() => jest.restoreAllMocks());

describe("AI consent screen (DOC-003, BR-AI-05)", () => {
    test("BR_AI_05_the_disclosure_is_shown_and_consent_is_off_by_default", async () => {
        const api = installApi(false);
        await mount();

        expect(await screen.findByTestId("receipt-analysis-consent-status")).toHaveTextContent(
            "Non accordé",
        );
        expect(screen.getByText(/Google Gemini/)).toBeTruthy();
        expect(screen.getByText(/aucune dépense n'est créée sans votre vérification/)).toBeTruthy();
        expect(screen.getByText(/retirer votre accord à tout moment/)).toBeTruthy();
        expect(api.puts()).toHaveLength(0);
    });

    test("BR_AI_05_granting_records_the_wording_version_shown", async () => {
        const api = installApi(false);
        await mount();

        await fireEvent.press(
            await screen.findByRole("button", { name: "Autoriser l'analyse des tickets" }),
        );

        await waitFor(() =>
            expect(screen.getByTestId("receipt-analysis-consent-status")).toHaveTextContent(
                "Accordé",
            ),
        );
        expect(api.puts()).toEqual([
            {
                key: "PUT /api/v1/me/ai-consents/RECEIPT_ANALYSIS",
                body: { granted: true, consentTextVersion: "receipt-analysis-v1" },
            },
        ]);
    });

    test("BR_AI_05_consent_is_withdrawn_in_one_tap", async () => {
        const api = installApi(true);
        await mount();

        await fireEvent.press(await screen.findByRole("button", { name: "Retirer mon accord" }));

        await waitFor(() =>
            expect(screen.getByTestId("receipt-analysis-consent-status")).toHaveTextContent(
                "Non accordé",
            ),
        );
        expect(api.puts()[0]!.body).toEqual({
            granted: false,
            consentTextVersion: "receipt-analysis-v1",
        });
    });

    test("a failed decision is reported and the state is unchanged", async () => {
        installApi(false, () => json(500, { code: "INTERNAL_ERROR", status: 500, title: "x" }));
        await mount();

        await fireEvent.press(
            await screen.findByRole("button", { name: "Autoriser l'analyse des tickets" }),
        );

        expect(await screen.findByTestId("privacy-error")).toBeTruthy();
        expect(screen.getByTestId("receipt-analysis-consent-status")).toHaveTextContent(
            "Non accordé",
        );
    });

    test("consent is never inferred from missing data", () => {
        expect(isGranted(undefined, "RECEIPT_ANALYSIS")).toBe(false);
        expect(isGranted([], "RECEIPT_ANALYSIS")).toBe(false);
        expect(
            isGranted([{ purpose: "INSIGHTS_ASSISTANT", granted: true }], "RECEIPT_ANALYSIS"),
        ).toBe(false);
        expect(
            isGranted([{ purpose: "RECEIPT_ANALYSIS", granted: true }], "RECEIPT_ANALYSIS"),
        ).toBe(true);
    });
});
