/** RFC 9457 Problem Details as emitted by the backend, with its stable machine-readable `code`. */
export interface FieldViolation {
    readonly field: string;
    readonly message: string;
}

export const UNKNOWN_ERROR_CODE = "UNKNOWN_ERROR";

export class ApiError extends Error {
    readonly code: string;
    readonly status: number;
    readonly title: string;
    readonly detail: string | undefined;
    readonly errors: readonly FieldViolation[];

    constructor(init: {
        code: string;
        status: number;
        title: string;
        detail?: string | undefined;
        errors?: readonly FieldViolation[];
    }) {
        super(init.detail ?? init.title);
        this.name = "ApiError";
        this.code = init.code;
        this.status = init.status;
        this.title = init.title;
        this.detail = init.detail;
        this.errors = init.errors ?? [];
    }
}

function isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === "object" && value !== null && !Array.isArray(value);
}

function parseViolations(value: unknown): FieldViolation[] {
    if (!Array.isArray(value)) return [];
    return value.flatMap((item): FieldViolation[] =>
        isRecord(item) && typeof item.field === "string" && typeof item.message === "string"
            ? [{ field: item.field, message: item.message }]
            : [],
    );
}

/**
 * Maps an error response body to a typed {@link ApiError}. Never throws: a body that is not a valid
 * Problem Details document (e.g. a gateway error) maps to {@link UNKNOWN_ERROR_CODE}. The server
 * `code` is the only value callers should branch on.
 */
export function parseProblem(body: unknown, httpStatus: number): ApiError {
    if (isRecord(body) && typeof body.code === "string" && body.code.length > 0) {
        return new ApiError({
            code: body.code,
            status: typeof body.status === "number" ? body.status : httpStatus,
            title: typeof body.title === "string" ? body.title : "",
            detail: typeof body.detail === "string" ? body.detail : undefined,
            errors: parseViolations(body.errors),
        });
    }
    return new ApiError({ code: UNKNOWN_ERROR_CODE, status: httpStatus, title: "" });
}
