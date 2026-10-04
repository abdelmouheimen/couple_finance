import { pendingEmail } from "@/features/identity/pendingEmail";
import { QueryClient } from "@tanstack/react-query";
import createClient from "openapi-fetch";
import { SessionManager } from "@/shared/auth/session";
import { secureRefreshTokenStorage } from "@/shared/auth/tokenStorage";
import { appConfig } from "@/shared/config/env";
import { createAuthedFetch } from "./authedFetch";
import type { paths } from "./generated/schema";

const baseFetch = (input: Request | string, init?: RequestInit) => globalThis.fetch(input, init);

/** App-wide session (tokens), API client and query cache. Created once. */
export const session = new SessionManager({
    storage: secureRefreshTokenStorage,
    fetch: baseFetch as typeof fetch,
    baseUrl: appConfig.apiBaseUrl,
});

export const queryClient = new QueryClient();

// Sign-out wipes every cached server response (no financial data survives the session).
session.onSignedOut(() => {
    queryClient.clear();
    pendingEmail.clear();
});

export const api = createClient<paths>({
    baseUrl: appConfig.apiBaseUrl,
    fetch: createAuthedFetch(session, (request) => baseFetch(request)),
});
