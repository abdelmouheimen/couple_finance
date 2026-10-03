/**
 * Typed, validated runtime configuration. Only public, non-secret values live here
 * (EXPO_PUBLIC_* variables are inlined into the bundle and readable by anyone).
 */
export interface AppConfig {
    /** Backend origin without trailing slash and without the /api/v1 prefix. */
    readonly apiBaseUrl: string;
}

const DEFAULT_LOCAL_API_BASE_URL = "http://localhost:8080";

export function parseAppConfig(env: { EXPO_PUBLIC_API_BASE_URL?: string | undefined }): AppConfig {
    const raw = env.EXPO_PUBLIC_API_BASE_URL?.trim() || DEFAULT_LOCAL_API_BASE_URL;
    let url: URL;
    try {
        url = new URL(raw);
    } catch {
        throw new Error("EXPO_PUBLIC_API_BASE_URL is not a valid URL");
    }
    if (url.username || url.password) {
        throw new Error("EXPO_PUBLIC_API_BASE_URL must not embed credentials");
    }
    // Cleartext http only for local development hosts (10.0.2.2 = Android emulator host).
    const isLocalHost = ["localhost", "127.0.0.1", "10.0.2.2"].includes(url.hostname);
    if (url.protocol !== "https:" && !(url.protocol === "http:" && isLocalHost)) {
        throw new Error("EXPO_PUBLIC_API_BASE_URL must be https (http only for local hosts)");
    }
    return { apiBaseUrl: raw.replace(/\/+$/, "") };
}

export const appConfig: AppConfig = parseAppConfig({
    EXPO_PUBLIC_API_BASE_URL: process.env.EXPO_PUBLIC_API_BASE_URL,
});
