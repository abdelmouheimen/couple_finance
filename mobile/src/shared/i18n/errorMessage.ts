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
        case "INVALID_AMOUNT_FORMAT":
            return strings.errors.amountInvalid;
        case "AMOUNT_NOT_POSITIVE":
            return strings.errors.amountNotPositive;
        case "AMOUNT_EXCEEDS_MAXIMUM":
            return strings.errors.amountTooLarge;
        case "EXPENSE_DATE_OUT_OF_RANGE":
        case "EXPENSE_REFUND_DATE_BEFORE_ORIGINAL":
            return strings.errors.dateOutOfRange;
        case "EXPENSE_ITEMS_SUM_MISMATCH":
        case "EXPENSE_ITEM_COUNT_INVALID":
        case "EXPENSE_ITEM_DUPLICATE_CATEGORY":
            return strings.errors.itemsMismatch;
        case "EXPENSE_CATEGORY_ARCHIVED":
        case "CATEGORY_ARCHIVED":
            return strings.errors.categoryArchived;
        case "CATEGORY_NOT_FOUND":
            return strings.errors.categoryNotFound;
        case "EXPENSE_MERCHANT_INVALID":
            return strings.errors.merchantInvalid;
        case "EXPENSE_SHARING_CHANGE_FORBIDDEN":
        case "EXPENSE_PERSONAL_PAYER_MISMATCH":
            return strings.errors.sharingForbidden;
        case "EXPENSE_NOT_FOUND":
            return strings.errors.expenseNotFound;
        case "EXPENSE_HAS_LIVE_REFUNDS":
            return strings.errors.hasLiveRefunds;
        case "REQUEST_IN_PROGRESS":
            return strings.errors.requestInProgress;
        case "VERSION_CONFLICT":
            return strings.errors.conflict;
        case "CATEGORY_NAME_ALREADY_EXISTS":
            return strings.errors.categoryNameTaken;
        case "SYSTEM_CATEGORY_IMMUTABLE":
            return strings.errors.categorySystem;
        case "CATEGORY_LIMIT_REACHED":
            return strings.errors.categoryLimit;
        case "EXPENSE_REFUND_OF_NOT_ALLOWED":
        case "EXPENSE_REFUND_ORIGINAL_INVALID":
        case "EXPENSE_REFUND_VISIBILITY_MISMATCH":
        case "EXPENSE_REFUND_EXCEEDS_ORIGINAL":
            return strings.errors.refundProblem;
        default:
            return strings.errorDefault;
    }
}

/** Server-side field violations keyed by field name, with a generic human message. */
export function fieldErrors(error: unknown): Record<string, string> {
    if (!(error instanceof ApiError)) return {};
    return Object.fromEntries(error.errors.map((v) => [v.field, strings.errors.invalidField]));
}
