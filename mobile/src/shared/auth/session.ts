import { type RefreshTokenStorage } from "./tokenStorage";

export type SessionStatus = "restoring" | "signedOut" | "signedIn" | "restoreFailed";

/** Result of a refresh attempt: `invalid` = server rejected the token; `unavailable` = could not tell. */
export type RefreshOutcome = "ok" | "invalid" | "unavailable";

export interface SessionTokens {
    readonly accessToken: string;
    readonly refreshToken: string;
}

interface Deps {
    readonly storage: RefreshTokenStorage;
    /** Plain (non-authenticated, non-retrying) fetch used only for the refresh call. */
    readonly fetch: typeof fetch;
    readonly baseUrl: string;
}

/**
 * Owns the session: the access token lives in memory ONLY, the refresh token in secure storage ONLY.
 * Refreshing is single-flight: any number of concurrent callers share one /auth/refresh call
 * (the backend rotates the token, so parallel refreshes would race).
 */
export class SessionManager {
    private accessToken: string | null = null;
    private status: SessionStatus = "restoring";
    /** Bumped by signOut(); a refresh started before it must not revive the session. */
    private generation = 0;
    private inFlight: Promise<RefreshOutcome> | null = null;
    private readonly listeners = new Set<() => void>();
    private readonly signedOutHandlers = new Set<() => void>();

    constructor(private readonly deps: Deps) {}

    getStatus = (): SessionStatus => this.status;
    getAccessToken = (): string | null => this.accessToken;

    subscribe = (listener: () => void): (() => void) => {
        this.listeners.add(listener);
        return () => {
            this.listeners.delete(listener);
        };
    };

    /** Registers a callback run when the session ends (used to wipe the query cache). */
    onSignedOut(handler: () => void): () => void {
        this.signedOutHandlers.add(handler);
        return () => {
            this.signedOutHandlers.delete(handler);
        };
    }

    /** Cold start: restore from the refresh token kept in secure storage, if any. */
    async restore(): Promise<void> {
        this.setStatus("restoring");
        let stored: string | null;
        try {
            stored = await this.deps.storage.get();
        } catch {
            this.setStatus("restoreFailed");
            return;
        }
        if (!stored) {
            this.setStatus("signedOut");
            return;
        }
        const outcome = await this.refresh();
        if (outcome === "unavailable") this.setStatus("restoreFailed");
    }

    /** Starts a session from fresh login tokens. */
    async establish(tokens: SessionTokens): Promise<void> {
        await this.deps.storage.set(tokens.refreshToken);
        this.accessToken = tokens.accessToken;
        this.setStatus("signedIn");
    }

    /** Ends the session locally: clears every token and notifies cache owners. Idempotent. */
    async signOut(): Promise<void> {
        this.generation++;
        const wasSignedOut = this.status === "signedOut" && this.accessToken === null;
        this.accessToken = null;
        try {
            await this.deps.storage.clear();
        } catch {
            // The in-memory state is already cleared; nothing else can be done.
        }
        if (wasSignedOut) return;
        this.status = "signedOut";
        this.signedOutHandlers.forEach((handler) => handler());
        this.emit();
    }

    /**
     * Called after a 401. If another request already refreshed (current token differs from the one
     * that failed) no new refresh is made. A definitive rejection signs the user out, once.
     */
    refreshAfterUnauthorized(failedToken: string): Promise<RefreshOutcome> {
        if (this.accessToken !== null && this.accessToken !== failedToken) {
            return Promise.resolve("ok");
        }
        return this.refresh();
    }

    private refresh(): Promise<RefreshOutcome> {
        this.inFlight ??= this.doRefresh().finally(() => {
            this.inFlight = null;
        });
        return this.inFlight;
    }

    private async doRefresh(): Promise<RefreshOutcome> {
        let refreshToken: string | null;
        try {
            refreshToken = await this.deps.storage.get();
        } catch {
            return "unavailable";
        }
        if (!refreshToken) {
            await this.signOut();
            return "invalid";
        }
        const startedGeneration = this.generation;
        let response: Response;
        try {
            response = await this.deps.fetch(`${this.deps.baseUrl}/api/v1/auth/refresh`, {
                method: "POST",
                headers: { "Content-Type": "application/json", Accept: "application/json" },
                body: JSON.stringify({ refreshToken }),
            });
        } catch {
            return "unavailable";
        }
        if (startedGeneration !== this.generation) return "invalid";
        if (response.status === 400 || response.status === 401) {
            await this.signOut();
            return "invalid";
        }
        if (!response.ok) return "unavailable";
        try {
            const body: unknown = await response.json();
            if (!isTokens(body)) return "unavailable";
            await this.establish(body);
            return "ok";
        } catch {
            return "unavailable";
        }
    }

    private setStatus(status: SessionStatus) {
        if (this.status === status) return;
        this.status = status;
        this.emit();
    }

    private emit() {
        this.listeners.forEach((listener) => listener());
    }
}

function isTokens(value: unknown): value is SessionTokens {
    if (typeof value !== "object" || value === null) return false;
    const v = value as Record<string, unknown>;
    return typeof v.accessToken === "string" && typeof v.refreshToken === "string";
}
