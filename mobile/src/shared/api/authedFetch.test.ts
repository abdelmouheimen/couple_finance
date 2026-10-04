import { SessionManager } from "@/shared/auth/session";
import { createAuthedFetch } from "./authedFetch";

function setup(refreshStatus = 200) {
    let stored: string | null = "refresh-0";
    const storage = {
        get: async () => stored,
        set: async (t: string) => {
            stored = t;
        },
        clear: async () => {
            stored = null;
        },
    };
    const refreshFetch = jest.fn(async () =>
        refreshStatus === 200
            ? new Response(JSON.stringify({ accessToken: "new", refreshToken: "refresh-1" }))
            : new Response("{}", { status: refreshStatus }),
    );
    const session = new SessionManager({ storage, fetch: refreshFetch, baseUrl: "http://x" });
    const base = jest.fn(async (req: Request) => {
        const ok = req.headers.get("Authorization") === "Bearer new";
        return new Response("{}", { status: ok ? 200 : 401 });
    });
    return { session, refreshFetch, base, authed: createAuthedFetch(session, base) };
}

describe("authed fetch", () => {
    test("N parallel 401s trigger exactly one refresh and all are retried", async () => {
        const { session, refreshFetch, authed } = setup();
        await session.establish({ accessToken: "old", refreshToken: "refresh-0" });
        const responses = await Promise.all(
            Array.from({ length: 4 }, (_, i) => authed(new Request(`http://x/api/v1/r${i}`))),
        );
        expect(responses.map((r) => r.status)).toEqual([200, 200, 200, 200]);
        expect(refreshFetch).toHaveBeenCalledTimes(1);
    });

    test("a failed refresh signs out once and returns the 401", async () => {
        const { session, authed } = setup(401);
        const onOut = jest.fn();
        session.onSignedOut(onOut);
        await session.establish({ accessToken: "old", refreshToken: "refresh-0" });
        const responses = await Promise.all([
            authed(new Request("http://x/api/v1/a")),
            authed(new Request("http://x/api/v1/b")),
        ]);
        expect(responses.map((r) => r.status)).toEqual([401, 401]);
        expect(onOut).toHaveBeenCalledTimes(1);
    });

    test("public auth endpoints carry no token and are never retried", async () => {
        const { session, base, authed, refreshFetch } = setup();
        await session.establish({ accessToken: "old", refreshToken: "refresh-0" });
        const res = await authed(new Request("http://x/api/v1/auth/login", { method: "POST" }));
        expect(res.status).toBe(401);
        expect(base).toHaveBeenCalledTimes(1);
        expect(base.mock.calls[0]?.[0].headers.get("Authorization")).toBeNull();
        expect(refreshFetch).not.toHaveBeenCalled();
    });
});
