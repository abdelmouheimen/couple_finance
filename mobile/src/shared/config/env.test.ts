import { parseAppConfig } from "./env";

describe("app config", () => {
    test("defaults to the local backend", () => {
        expect(parseAppConfig({}).apiBaseUrl).toBe("http://localhost:8080");
    });
    test("strips trailing slashes", () => {
        expect(
            parseAppConfig({ EXPO_PUBLIC_API_BASE_URL: "https://api.example.com//" }).apiBaseUrl,
        ).toBe("https://api.example.com");
    });
    test("rejects invalid or non-http URLs", () => {
        expect(() => parseAppConfig({ EXPO_PUBLIC_API_BASE_URL: "nope" })).toThrow();
        expect(() => parseAppConfig({ EXPO_PUBLIC_API_BASE_URL: "ftp://x" })).toThrow();
        expect(() =>
            parseAppConfig({ EXPO_PUBLIC_API_BASE_URL: "http://api.example.com" }),
        ).toThrow();
        expect(() =>
            parseAppConfig({ EXPO_PUBLIC_API_BASE_URL: "https://u:p@api.example.com" }),
        ).toThrow();
    });
    test("allows http for local development hosts", () => {
        expect(
            parseAppConfig({ EXPO_PUBLIC_API_BASE_URL: "http://10.0.2.2:8080" }).apiBaseUrl,
        ).toBe("http://10.0.2.2:8080");
    });
});
