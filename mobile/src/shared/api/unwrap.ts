import { ApiError, parseProblem } from "./problem";

export const NETWORK_ERROR_CODE = "NETWORK_ERROR";

/** Turns an openapi-fetch result into its data, or throws a typed {@link ApiError}. */
export async function unwrap<D>(
    call: Promise<{ data?: D; error?: unknown; response: Response }>,
): Promise<D> {
    let result;
    try {
        result = await call;
    } catch {
        throw new ApiError({ code: NETWORK_ERROR_CODE, status: 0, title: "" });
    }
    if (result.error !== undefined || !result.response.ok) {
        throw parseProblem(result.error, result.response.status);
    }
    return result.data as D;
}
