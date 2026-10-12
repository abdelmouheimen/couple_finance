import type { RefreshTokenStorage } from "./tokenStorage";

/**
 * Web build only (used for the design preview and visual regression screenshots; the product targets
 * iOS/Android). Browsers have no secure enclave, so the refresh token is never written to disk here:
 * it lives in memory for the lifetime of the page and a reload signs the user out.
 */
let token: string | null = null;

export const secureRefreshTokenStorage: RefreshTokenStorage = {
    get: () => Promise.resolve(token),
    set: (value) => {
        token = value;
        return Promise.resolve();
    },
    clear: () => {
        token = null;
        return Promise.resolve();
    },
};
