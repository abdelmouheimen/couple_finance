/** Email typed at sign-in/sign-up, kept in memory only so "resend verification" needs no re-typing. */
let pending = "";
export const pendingEmail = {
    get: () => pending,
    set: (email: string) => {
        pending = email;
    },
    clear: () => {
        pending = "";
    },
};
