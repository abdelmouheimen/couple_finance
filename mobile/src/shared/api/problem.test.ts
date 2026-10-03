import { ApiError, UNKNOWN_ERROR_CODE, parseProblem } from "./problem";

describe("Problem Details mapping (RFC 9457)", () => {
    test("exposes the stable machine code and field violations", () => {
        const err = parseProblem(
            {
                type: "about:blank",
                title: "Bad Request",
                status: 400,
                detail: "The request contains invalid values.",
                code: "VALIDATION_FAILED",
                errors: [{ field: "amount", message: "must be positive" }, { bogus: 1 }],
            },
            400,
        );
        expect(err).toBeInstanceOf(ApiError);
        expect(err.code).toBe("VALIDATION_FAILED");
        expect(err.status).toBe(400);
        expect(err.errors).toEqual([{ field: "amount", message: "must be positive" }]);
    });

    test("falls back to the HTTP status when the body has no status", () => {
        expect(parseProblem({ code: "NOT_FOUND", title: "Not Found" }, 404).status).toBe(404);
    });

    test.each([undefined, null, "oops", 42, [], {}, { code: "" }, { code: 5 }])(
        "non Problem Details body %p maps to UNKNOWN_ERROR",
        (body) => {
            const err = parseProblem(body, 502);
            expect(err.code).toBe(UNKNOWN_ERROR_CODE);
            expect(err.status).toBe(502);
        },
    );
});
