import { type SessionManager } from "@/shared/auth/session";

type Fetch = (input: Request) => Promise<Response>;

const AUTH_PREFIX = "/api/v1/auth/";
const LOGOUT_PREFIX = `${AUTH_PREFIX}logout`;

function withBearer(request: Request, token: string | null): Request {
    if (token) request.headers.set("Authorization", `Bearer ${token}`);
    return request;
}

/**
 * Fetch wrapper: adds the in-memory access token and, on a 401 from a protected endpoint, refreshes
 * (single-flight, via the session) and retries the request once with the new token.
 * Public auth endpoints (login, register, ...) never carry a token and are never retried;
 * logout carries the token and is retried once after a refresh.
 */
export function createAuthedFetch(session: SessionManager, baseFetch: Fetch): Fetch {
    return async (request) => {
        const p = new URL(request.url).pathname;
        // Logout is a protected call: an expired access token is refreshed and the call retried once,
        // so the server-side session/refresh token is really revoked.
        const isLogout = p.startsWith(LOGOUT_PREFIX);
        if (!isLogout && p.startsWith(AUTH_PREFIX)) return baseFetch(request);
        const retry = request.clone();
        const used = session.getAccessToken();
        const first = await baseFetch(withBearer(request, used));
        if (first.status !== 401 || used === null) return first;
        const outcome = await session.refreshAfterUnauthorized(used);
        if (outcome !== "ok") return first;
        return baseFetch(withBearer(retry, session.getAccessToken()));
    };
}
