import * as SecureStore from "expo-secure-store";

/** Persistence for the refresh token. The ONLY place a token touches disk: secure storage. */
export interface RefreshTokenStorage {
    get(): Promise<string | null>;
    set(token: string): Promise<void>;
    clear(): Promise<void>;
}

// SecureStore keys allow only alphanumerics, ".", "-" and "_".
const KEY = "couplefinance.refresh-token";
const OPTIONS: SecureStore.SecureStoreOptions = {
    keychainAccessible: SecureStore.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
};

export const secureRefreshTokenStorage: RefreshTokenStorage = {
    get: () => SecureStore.getItemAsync(KEY, OPTIONS),
    set: (token) => SecureStore.setItemAsync(KEY, token, OPTIONS),
    clear: () => SecureStore.deleteItemAsync(KEY, OPTIONS),
};
