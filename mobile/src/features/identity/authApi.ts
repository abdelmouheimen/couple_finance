import { api, session } from "@/shared/api/instance";
import { unwrap } from "@/shared/api/unwrap";

export interface Credentials {
    readonly email: string;
    readonly password: string;
}

/** Logs in and starts the session (tokens: memory + secure storage only). */
export async function signIn(credentials: Credentials): Promise<void> {
    const tokens = await unwrap(
        api.POST("/api/v1/auth/login", {
            body: { email: credentials.email, password: credentials.password },
        }),
    );
    await session.establish({
        accessToken: tokens.accessToken ?? "",
        refreshToken: tokens.refreshToken ?? "",
    });
}

export async function signUp(input: {
    email: string;
    password: string;
    displayName: string;
    locale?: string | undefined;
}): Promise<void> {
    await unwrap(
        api.POST("/api/v1/auth/register", {
            body: {
                email: input.email,
                password: input.password,
                displayName: input.displayName,
                ...(input.locale ? { locale: input.locale } : {}),
            },
        }),
    );
}

export async function verifyEmail(token: string): Promise<void> {
    await unwrap(api.POST("/api/v1/auth/verify-email", { body: { token } }));
}

export async function resendVerification(email: string): Promise<void> {
    await unwrap(api.POST("/api/v1/auth/resend-verification", { body: { email } }));
}

/** Best-effort server logout, then always clears local state. */
export async function signOut(): Promise<void> {
    try {
        await unwrap(api.POST("/api/v1/auth/logout"));
    } catch {
        // The session is cleared locally regardless of the server outcome.
    }
    await session.signOut();
}
