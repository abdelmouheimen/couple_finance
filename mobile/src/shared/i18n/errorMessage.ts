import { ApiError } from "@/shared/api/problem";
import { NETWORK_ERROR_CODE } from "@/shared/api/unwrap";
import { strings } from "./strings";

/** Maps an error to a human message. Raw server codes and details are never shown to the user. */
export function errorMessage(error: unknown): string {
    if (!(error instanceof ApiError)) return strings.errorDefault;
    if (error.code === NETWORK_ERROR_CODE) return strings.errors.network;
    if (error.status === 429 || error.code === "RATE_LIMITED") return strings.errors.rateLimited;
    switch (error.code) {
        case "INVALID_CREDENTIALS":
            return strings.errors.invalidCredentials;
        case "INVALID_OR_EXPIRED_TOKEN":
            return strings.errors.invalidToken;
        case "EMAIL_NOT_VERIFIED":
            return strings.errors.emailNotVerified;
        case "ALREADY_IN_HOUSEHOLD":
            return strings.errors.alreadyInHousehold;
        case "HOUSEHOLD_READ_ONLY":
            return strings.errors.readOnly;
        case "INVITATION_NOT_ALLOWED":
            return strings.errors.invitationNotAllowed;
        case "INVITATION_NOT_REVOCABLE":
            return strings.errors.invitationNotRevocable;
        default:
            return strings.errorDefault;
    }
}

/** Server-side field violations keyed by field name, with a generic human message. */
export function fieldErrors(error: unknown): Record<string, string> {
    if (!(error instanceof ApiError)) return {};
    return Object.fromEntries(error.errors.map((v) => [v.field, strings.errors.invalidField]));
}
