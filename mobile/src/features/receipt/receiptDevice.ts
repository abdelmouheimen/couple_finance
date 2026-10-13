/**
 * Device boundary of receipt import (camera, gallery, PDF, image conversion, cache, sharing). Every native call of
 * the feature goes through this module, so screens stay testable with one mock.
 *
 * Privacy (security.md section 9): captured and converted files live in the app cache only, are never saved to
 * the gallery, and are deleted after upload ({@link discard}).
 */
import * as DocumentPicker from "expo-document-picker";
import { Directory, File, Paths } from "expo-file-system";
import { ImageManipulator, SaveFormat } from "expo-image-manipulator";
import * as ImagePicker from "expo-image-picker";
import * as Sharing from "expo-sharing";
import type { LocalReceiptFile } from "./receiptApi";

/** BR-RCP-01: at most five photos per receipt. */
export const MAX_RECEIPT_PHOTOS = 5;
/** Client-side compression (F4): long side and JPEG quality; the server re-encodes anyway. */
const MAX_SIDE = 2400;
const JPEG_QUALITY = 0.8;

export type PickOutcome =
    { kind: "picked"; files: LocalReceiptFile[] } | { kind: "cancelled" } | { kind: "denied" };

/** Re-encodes any picked image (HEIC included) as an upright JPEG of bounded size. */
async function toJpeg(uri: string, width: number, height: number, index: number) {
    const context = ImageManipulator.manipulate(uri);
    if (Math.max(width, height) > MAX_SIDE) {
        context.resize(width >= height ? { width: MAX_SIDE } : { height: MAX_SIDE });
    }
    const image = await context.renderAsync();
    const saved = await image.saveAsync({ format: SaveFormat.JPEG, compress: JPEG_QUALITY });
    return { uri: saved.uri, name: `page-${index + 1}.jpg`, mimeType: "image/jpeg" as const };
}

async function fromPicker(
    result: ImagePicker.ImagePickerResult,
    limit: number,
): Promise<PickOutcome> {
    if (result.canceled) return { kind: "cancelled" };
    const assets = result.assets.slice(0, limit);
    const files = await Promise.all(assets.map((a, i) => toJpeg(a.uri, a.width, a.height, i)));
    discard(assets.map((a) => a.uri)); // the picker copies; keep only the converted JPEGs
    return { kind: "picked", files };
}

/** One photo from the system camera (multi-shot receipts call it once per part). */
export async function captureFromCamera(): Promise<PickOutcome> {
    const permission = await ImagePicker.requestCameraPermissionsAsync();
    if (!permission.granted) return { kind: "denied" };
    return fromPicker(
        await ImagePicker.launchCameraAsync({ mediaTypes: ["images"], quality: 1, exif: false }),
        1,
    );
}

/** Up to {@code limit} photos from the gallery, in the selected order. */
export async function pickFromGallery(limit: number): Promise<PickOutcome> {
    return fromPicker(
        await ImagePicker.launchImageLibraryAsync({
            mediaTypes: ["images"],
            allowsMultipleSelection: limit > 1,
            selectionLimit: limit,
            orderedSelection: true,
            quality: 1,
            exif: false,
        }),
        limit,
    );
}

/** One PDF document (BR-RCP-01: a single PDF of at most five pages, checked by the server). */
export async function pickPdf(): Promise<PickOutcome> {
    const result = await DocumentPicker.getDocumentAsync({
        type: "application/pdf",
        copyToCacheDirectory: true,
        multiple: false,
    });
    if (result.canceled || !result.assets[0]) return { kind: "cancelled" };
    return {
        kind: "picked",
        files: [{ uri: result.assets[0].uri, name: "receipt.pdf", mimeType: "application/pdf" }],
    };
}

/**
 * A local file as a multipart part. The app's global fetch is `expo/fetch`, which reads parts through the Blob
 * interface and rejects React Native's `{uri, name, type}` descriptors before anything is sent.
 */
export function uploadPart(file: LocalReceiptFile): Blob {
    return new File(file.uri);
}

/** Deletes local copies (best effort): captured, picked or downloaded receipt files never linger in the cache. */
export function discard(uris: readonly string[]): void {
    for (const uri of uris) {
        try {
            const file = new File(uri);
            if (file.exists) file.delete();
        } catch {
            // Already gone or outside the app sandbox: nothing to clean.
        }
    }
}

/** Downloads a page through its short-lived URL into the cache, opens the share sheet, then deletes the copy. */
export async function sharePage(url: string, receiptId: string, position: number): Promise<void> {
    const directory = new Directory(Paths.cache, "receipt-share");
    if (!directory.exists) directory.create();
    const target = new File(directory, `ticket-${receiptId.slice(0, 8)}-${position}.jpg`);
    if (target.exists) target.delete();
    const file = await File.downloadFileAsync(url, target);
    try {
        await Sharing.shareAsync(file.uri, { mimeType: "image/jpeg", dialogTitle: "Ticket" });
    } finally {
        discard([file.uri]);
    }
}
