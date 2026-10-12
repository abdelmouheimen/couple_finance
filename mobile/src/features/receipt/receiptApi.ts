import type { components } from "@/shared/api/client";
import { api, authedFetch } from "@/shared/api/instance";
import { ApiError, parseProblem } from "@/shared/api/problem";
import { NETWORK_ERROR_CODE, unwrap } from "@/shared/api/unwrap";
import { appConfig } from "@/shared/config/env";

export type Receipt = components["schemas"]["Receipt"];
export type ReceiptList = components["schemas"]["ReceiptList"];
export type ReceiptPageUrl = components["schemas"]["ReceiptPageUrl"];

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

export async function deleteReceipt(id: string): Promise<void> {
    await unwrap(api.DELETE("/api/v1/receipts/{id}", { params: { path: { id } } }));
}

/** The multipart body: React Native streams `{uri, name, type}` parts from the local file system. */
export function receiptFormData(files: readonly LocalReceiptFile[]): FormData {
    const body = new FormData();
    for (const file of files) {
        // React Native's FormData accepts a file descriptor object in place of a Blob.
        body.append("files", {
            uri: file.uri,
            name: file.name,
            type: file.mimeType,
        } as unknown as Blob);
    }
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
