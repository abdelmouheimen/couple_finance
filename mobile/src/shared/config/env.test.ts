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
    test("allows http on a private LAN address only in development builds", () => {
        const lan = { EXPO_PUBLIC_API_BASE_URL: "http://192.168.1.42:8080" };
        expect(parseAppConfig(lan, { allowPrivateLanHttp: true }).apiBaseUrl).toBe(
            "http://192.168.1.42:8080",
        );
        expect(
            parseAppConfig(
                { EXPO_PUBLIC_API_BASE_URL: "http://10.1.2.3:8080" },
                { allowPrivateLanHttp: true },
            ).apiBaseUrl,
        ).toBe("http://10.1.2.3:8080");
        expect(
            parseAppConfig(
                { EXPO_PUBLIC_API_BASE_URL: "http://172.20.0.5:8080" },
                { allowPrivateLanHttp: true },
            ).apiBaseUrl,
        ).toBe("http://172.20.0.5:8080");
        expect(() => parseAppConfig(lan)).toThrow();
        expect(() => parseAppConfig(lan, { allowPrivateLanHttp: false })).toThrow();
    });
    test("never allows http on public or look-alike hosts, even in development", () => {
        for (const url of [
            "http://8.8.8.8:8080",
            "http://172.32.0.1:8080",
            "http://192.169.1.1:8080",
            "http://192.168.1.42.evil.example:8080",
            "http://api.example.com",
        ]) {
            expect(() =>
                parseAppConfig({ EXPO_PUBLIC_API_BASE_URL: url }, { allowPrivateLanHttp: true }),
            ).toThrow();
        }
    });
});
