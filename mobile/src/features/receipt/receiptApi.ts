import type { components } from "@/shared/api/client";
import { api, authedFetch } from "@/shared/api/instance";
import { ApiError, parseProblem } from "@/shared/api/problem";
import { NETWORK_ERROR_CODE, unwrap } from "@/shared/api/unwrap";
import { appConfig } from "@/shared/config/env";
import { uploadPart } from "./receiptDevice";

export type Receipt = components["schemas"]["Receipt"];
export type ReceiptList = components["schemas"]["ReceiptList"];
export type ReceiptPageUrl = components["schemas"]["ReceiptPageUrl"];
export type ReceiptReview = components["schemas"]["ReceiptReview"];
export type ReceiptDuplicate = components["schemas"]["ReceiptDuplicate"];
export type ReceiptLink = components["schemas"]["ReceiptLink"];
export type ConfirmReceiptRequest = components["schemas"]["ConfirmReceiptRequest"];
export type AttachReceiptRequest = components["schemas"]["AttachReceiptRequest"];

/** A file prepared on the device for upload: a local URI the multipart body can stream. */
export interface LocalReceiptFile {
    readonly uri: string;
    readonly name: string;
    readonly mimeType: "image/jpeg" | "application/pdf";
}

export const RECEIPTS_KEY = ["receipts"] as const;

export function listReceipts(cursor?: string): Promise<ReceiptList> {
    return unwrap(
        api.GET("/api/v1/receipts", {
            params: { query: { limit: 20, ...(cursor ? { cursor } : {}) } },
        }),
    );
}

export function getReceipt(id: string): Promise<Receipt> {
    return unwrap(api.GET("/api/v1/receipts/{id}", { params: { path: { id } } }));
}

/** A presigned URL valid 5 minutes: kept in memory only, never persisted, logged or cached. */
export function getReceiptPageUrl(id: string, position: number): Promise<ReceiptPageUrl> {
    return unwrap(
        api.GET("/api/v1/receipts/{id}/pages/{position}/url", {
            params: { path: { id, position } },
        }),
    );
}

/**
 * Queues the analysis of an open receipt (BR-RCP-17): a manual retry of a failure, or the first analysis of a receipt
 * uploaded before the user consented. Needs the caller's receipt-analysis consent (BR-AI-05).
 */
export function requestReceiptAnalysis(id: string): Promise<Receipt> {
    return unwrap(api.POST("/api/v1/receipts/{id}/analysis", { params: { path: { id } } }));
}

/** States of a receipt that is still an open draft of its uploader (deletable, BR-RCP-20). */
export function isOpenReceipt(receipt: Receipt): boolean {
    return !["CONFIRMED", "ATTACHED", "ABANDONED"].includes(receipt.state);
}

/** The analysis is queued or running: the screen polls until it settles. */
export function isAnalysisPending(receipt: Receipt): boolean {
    return receipt.state === "QUEUED" || receipt.state === "ANALYSING";
}

/** The draft of an open receipt as proposals, with duplicate warnings (BR-RCP-10, BR-RCP-14). */
export function getReceiptReview(id: string): Promise<ReceiptReview> {
    return unwrap(api.GET("/api/v1/receipts/{id}/review", { params: { path: { id } } }));
}

/** The form values as entered: the amount is the string the member typed, never recomputed. */
interface ReviewedValues {
    amount: string;
    categoryId: string | null;
    date: string;
    merchant: string;
    note: string;
    sharingType: "SHARED" | "PERSONAL";
}

/**
 * The confirmation of a reviewed receipt (BR-RCP-10): the acknowledged total and date ARE the submitted values (the
 * member confirms them on the acknowledgement sheet), and every field the review flagged carries the value the
 * member kept or typed.
 */
export function buildConfirmRequest(
    values: ReviewedValues,
    review: ReceiptReview,
    ctx: { currency: string; paidByUserId: string },
): ConfirmReceiptRequest {
    if (!values.categoryId) throw new Error("categoryId is required");
    const money = { amount: values.amount, currency: ctx.currency };
    const merchant = values.merchant.trim();
    const note = values.note.trim();
    const chosen: Record<string, string> = {
        total: values.amount,
        date: values.date,
        merchant,
        currency: ctx.currency,
    };
    const resolutions: Record<string, string> = {};
    for (const field of review.unresolvedFields) resolutions[field] = chosen[field] ?? "";
    return {
        version: review.version,
        kind: review.refundProposed ? "REFUND" : "EXPENSE",
        amount: money,
        date: values.date,
        categoryId: values.categoryId,
        paidByUserId: ctx.paidByUserId,
        sharingType: values.sharingType,
        ...(merchant ? { merchant } : {}),
        ...(note ? { note } : {}),
        acknowledgedTotal: money,
        acknowledgedDate: values.date,
        ...(review.unresolvedFields.length > 0 ? { resolutions } : {}),
    } as ConfirmReceiptRequest;
}

/** Creates the expense from the acknowledged values and links the receipt, exactly once (BR-RCP-15). */
export function confirmReceipt(
    id: string,
    body: ConfirmReceiptRequest,
    idempotencyKey: string,
): Promise<ReceiptLink> {
    return unwrap(
        api.POST("/api/v1/receipts/{id}/confirm", {
            params: { path: { id }, header: { "Idempotency-Key": idempotencyKey } },
            body,
        }),
    );
}

/** Keeps the receipt as evidence of an existing expense instead of creating a duplicate (BR-RCP-14). */
export function attachReceipt(
    id: string,
    body: AttachReceiptRequest,
    idempotencyKey: string,
): Promise<ReceiptLink> {
    return unwrap(
        api.POST("/api/v1/receipts/{id}/attach", {
            params: { path: { id }, header: { "Idempotency-Key": idempotencyKey } },
            body,
        }),
    );
}

export async function deleteReceipt(id: string): Promise<void> {
    await unwrap(api.DELETE("/api/v1/receipts/{id}", { params: { path: { id } } }));
}

/** The multipart body: one `files` part per page, read from the local file system by the fetch implementation. */
export function receiptFormData(files: readonly LocalReceiptFile[]): FormData {
    const body = new FormData();
    for (const file of files) body.append("files", uploadPart(file), file.name);
    return body;
}

/**
 * Uploads a receipt (BR-RCP-01). The same Idempotency-Key is reused by the caller for a retry of the same files, so
 * a network failure can never create a duplicate receipt. Multipart is sent through the authenticated fetch (the
 * generated client only speaks JSON).
 */
export async function uploadReceipt(
    files: readonly LocalReceiptFile[],
    idempotencyKey: string,
): Promise<Receipt> {
    let response: Response;
    try {
        response = await authedFetch(
            new Request(`${appConfig.apiBaseUrl}/api/v1/receipts`, {
                method: "POST",
                headers: { "Idempotency-Key": idempotencyKey, Accept: "application/json" },
                body: receiptFormData(files),
            }),
        );
    } catch {
        throw new ApiError({ code: NETWORK_ERROR_CODE, status: 0, title: "" });
    }
    const text = await response.text();
    let body: unknown = undefined;
    try {
        body = text === "" ? undefined : JSON.parse(text);
    } catch {
        body = undefined;
    }
    if (!response.ok) throw parseProblem(body, response.status);
    return body as Receipt;
}
