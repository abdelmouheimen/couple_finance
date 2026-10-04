/**
 * Typed, validated runtime configuration. Only public, non-secret values live here
 * (EXPO_PUBLIC_* variables are inlined into the bundle and readable by anyone).
 */
export interface AppConfig {
    /** Backend origin without trailing slash and without the /api/v1 prefix. */
    readonly apiBaseUrl: string;
}

const DEFAULT_LOCAL_API_BASE_URL = "http://localhost:8080";

/** RFC 1918 private IPv4 address (a development PC on the same Wi-Fi as a physical phone). */
function isPrivateLanIpv4(hostname: string): boolean {
    const match = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(hostname);
    if (!match) return false;
    const [a, b] = [Number(match[1]), Number(match[2])];
    return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

export function parseAppConfig(
    env: { EXPO_PUBLIC_API_BASE_URL?: string | undefined },
    options: { allowPrivateLanHttp?: boolean } = {},
): AppConfig {
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
    // Cleartext http only for local development hosts (10.0.2.2 = Android emulator host) and, in development
    // builds only, private LAN addresses (physical phone in Expo Go: scripts/start-mobile-local.ps1).
    const isLocalHost =
        ["localhost", "127.0.0.1", "10.0.2.2"].includes(url.hostname) ||
        (options.allowPrivateLanHttp === true && isPrivateLanIpv4(url.hostname));
    if (url.protocol !== "https:" && !(url.protocol === "http:" && isLocalHost)) {
        throw new Error("EXPO_PUBLIC_API_BASE_URL must be https (http only for local hosts)");
    }
    return { apiBaseUrl: raw.replace(/\/+$/, "") };
}

export const appConfig: AppConfig = parseAppConfig(
    { EXPO_PUBLIC_API_BASE_URL: process.env.EXPO_PUBLIC_API_BASE_URL },
    { allowPrivateLanHttp: __DEV__ },
);
