import { SessionManager } from "./session";
import { type RefreshTokenStorage } from "./tokenStorage";

function memoryStorage(initial: string | null = null) {
    let value = initial;
    const storage: RefreshTokenStorage = {
        get: jest.fn(async () => value),
        set: jest.fn(async (t: string) => {
            value = t;
        }),
        clear: jest.fn(async () => {
            value = null;
        }),
    };
    return storage;
}

function tokenResponse(access: string, refresh: string) {
    return new Response(JSON.stringify({ accessToken: access, refreshToken: refresh }), {
        status: 200,
    });
}

describe("SessionManager", () => {
    test("restore without a stored refresh token ends signed out", async () => {
        const fetchMock = jest.fn();
        const s = new SessionManager({
            storage: memoryStorage(),
            fetch: fetchMock,
            baseUrl: "http://x",
        });
        await s.restore();
        expect(s.getStatus()).toBe("signedOut");
        expect(fetchMock).not.toHaveBeenCalled();
    });

    test("restore refreshes from secure storage; access token stays in memory", async () => {
        const storage = memoryStorage("stored-refresh");
        const fetchMock = jest.fn(async () => tokenResponse("access-1", "refresh-2"));
        const s = new SessionManager({ storage, fetch: fetchMock, baseUrl: "http://x" });
        await s.restore();
        expect(s.getStatus()).toBe("signedIn");
        expect(s.getAccessToken()).toBe("access-1");
        expect(storage.set).toHaveBeenCalledWith("refresh-2");
    });

    test("restore with unreachable server does not sign out (restoreFailed)", async () => {
        const storage = memoryStorage("stored-refresh");
        const s = new SessionManager({
            storage,
            fetch: jest.fn(async () => {
                throw new Error("offline");
            }),
            baseUrl: "http://x",
        });
        await s.restore();
        expect(s.getStatus()).toBe("restoreFailed");
        expect(storage.clear).not.toHaveBeenCalled();
    });

    test("parallel refreshes share one call (single flight)", async () => {
        const storage = memoryStorage("r");
        const fetchMock = jest.fn(async () => tokenResponse("a2", "r2"));
        const s = new SessionManager({ storage, fetch: fetchMock, baseUrl: "http://x" });
        await s.establish({ accessToken: "a1", refreshToken: "r" });
        const results = await Promise.all(
            Array.from({ length: 5 }, () => s.refreshAfterUnauthorized("a1")),
        );
        expect(results).toEqual(Array(5).fill("ok"));
        expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    test("a rejected refresh signs out exactly once and wipes caches", async () => {
        const storage = memoryStorage("r");
        const fetchMock = jest.fn(async () => new Response("{}", { status: 401 }));
        const s = new SessionManager({ storage, fetch: fetchMock, baseUrl: "http://x" });
        const onOut = jest.fn();
        s.onSignedOut(onOut);
        await s.establish({ accessToken: "a1", refreshToken: "r" });
        await Promise.all([s.refreshAfterUnauthorized("a1"), s.refreshAfterUnauthorized("a1")]);
        expect(onOut).toHaveBeenCalledTimes(1);
        expect(s.getStatus()).toBe("signedOut");
        expect(s.getAccessToken()).toBeNull();
        expect(storage.clear).toHaveBeenCalled();
    });

    test("signOut clears tokens and is idempotent", async () => {
        const storage = memoryStorage("r");
        const s = new SessionManager({ storage, fetch: jest.fn(), baseUrl: "http://x" });
        const onOut = jest.fn();
        s.onSignedOut(onOut);
        await s.establish({ accessToken: "a", refreshToken: "r" });
        await s.signOut();
        await s.signOut();
        expect(onOut).toHaveBeenCalledTimes(1);
        expect(await storage.get()).toBeNull();
    });

    test("a refresh in flight when signOut runs does not revive the session", async () => {
        const storage = memoryStorage("stored-refresh");
        let release: (r: Response) => void = () => undefined;
        const fetchMock = jest.fn(
            () =>
                new Promise<Response>((resolve) => {
                    release = resolve;
                }),
        );
        const s = new SessionManager({ storage, fetch: fetchMock, baseUrl: "http://x" });
        const restoring = s.restore();
        await new Promise((r) => setTimeout(r, 0));
        await s.signOut();
        release(tokenResponse("access-1", "refresh-2"));
        await restoring;
        expect(s.getStatus()).toBe("signedOut");
        expect(s.getAccessToken()).toBeNull();
        expect(storage.set).not.toHaveBeenCalled();
    });
});
