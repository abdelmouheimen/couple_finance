import * as SecureStore from "expo-secure-store";
import { secureRefreshTokenStorage } from "./tokenStorage";

jest.mock("expo-secure-store", () => ({
    AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY: 1,
    getItemAsync: jest.fn(async () => "stored"),
    setItemAsync: jest.fn(async () => undefined),
    deleteItemAsync: jest.fn(async () => undefined),
}));

describe("refresh token storage", () => {
    test("the refresh token only ever goes through secure storage", async () => {
        await secureRefreshTokenStorage.set("tok");
        expect(SecureStore.setItemAsync).toHaveBeenCalledWith(
            expect.any(String),
            "tok",
            expect.objectContaining({ keychainAccessible: 1 }),
        );
        expect(await secureRefreshTokenStorage.get()).toBe("stored");
        await secureRefreshTokenStorage.clear();
        expect(SecureStore.deleteItemAsync).toHaveBeenCalled();
    });

    test("never logs the token", async () => {
        const spies = (["log", "info", "warn", "error", "debug"] as const).map((m) =>
            jest.spyOn(console, m).mockImplementation(() => undefined),
        );
        await secureRefreshTokenStorage.set("super-secret-token");
        spies.forEach((s) => expect(s).not.toHaveBeenCalled());
        spies.forEach((s) => s.mockRestore());
    });
});
