import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { screen, waitFor } from "@testing-library/react-native";
import { Text } from "react-native";
import { renderUi } from "@/shared/ui/testing";
import { HouseholdProvider, useHouseholdState } from "./HouseholdProvider";
import { ReadOnlyBanner } from "./ReadOnlyBanner";

const household = (status: string) => ({
    id: "11111111-1111-7111-8111-111111111111",
    name: "Home",
    currency: "EUR",
    timezone: "Europe/Paris",
    periodStartDay: 1,
    status,
    version: 1,
});

function mockFetch(status: number, body: unknown) {
    const fn = jest.fn(
        async () =>
            new Response(JSON.stringify(body), {
                status,
                headers: { "Content-Type": "application/json" },
            }),
    );
    jest.spyOn(globalThis, "fetch").mockImplementation(fn as unknown as typeof fetch);
    return fn;
}

function Probe() {
    const { state } = useHouseholdState();
    return <Text testID="state">{state.kind}</Text>;
}

async function mount() {
    const client = new QueryClient();
    await renderUi(
        <QueryClientProvider client={client}>
            <HouseholdProvider now={() => new Date("2026-03-15T10:00:00Z")}>
                <Probe />
                <ReadOnlyBanner />
            </HouseholdProvider>
        </QueryClientProvider>,
    );
}

afterEach(() => jest.restoreAllMocks());

describe("household bootstrap", () => {
    test("404 HOUSEHOLD_NOT_FOUND means no household (route to creation)", async () => {
        mockFetch(404, { code: "HOUSEHOLD_NOT_FOUND", status: 404, title: "x" });
        await mount();
        await waitFor(() => expect(screen.getByTestId("state").children).toContain("none"));
    });

    test("403 EMAIL_NOT_VERIFIED yields the verify-email state", async () => {
        mockFetch(403, { code: "EMAIL_NOT_VERIFIED", status: 403, title: "x" });
        await mount();
        await waitFor(() =>
            expect(screen.getByTestId("state").children).toContain("emailNotVerified"),
        );
    });

    test("other errors are surfaced as an error state (retryable)", async () => {
        mockFetch(500, { code: "INTERNAL_ERROR", status: 500, title: "x" });
        await mount();
        await waitFor(() => expect(screen.getByTestId("state").children).toContain("error"));
    });

    test("an ACTIVE household is ready and shows no read-only banner", async () => {
        mockFetch(200, household("ACTIVE"));
        await mount();
        await waitFor(() => expect(screen.getByTestId("state").children).toContain("ready"));
        expect(screen.queryByRole("alert")).toBeNull();
    });

    test("BR-HH-10: a DISSOLVED household shows the read-only banner", async () => {
        mockFetch(200, household("DISSOLVED"));
        await mount();
        expect(await screen.findByRole("alert")).toBeTruthy();
    });
});
