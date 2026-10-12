import { strings } from "@/shared/i18n/strings";
import type { Receipt } from "./receiptApi";

/** Display date of an upload instant, in the device locale (display only). */
export function receiptDate(instant: string): string {
    const date = new Date(instant);
    if (Number.isNaN(date.getTime())) return instant;
    return date.toLocaleDateString(undefined, { dateStyle: "medium" });
}

export function receiptStateLabel(receipt: Receipt): string {
    return strings.receipt.state[receipt.state] ?? receipt.state;
}

export function receiptSummary(receipt: Receipt): string {
    return `${strings.receipt.kind[receipt.sourceKind]} · ${strings.receipt.pages(receipt.pages.length)}`;
}

export type KindFilter = "ALL" | Receipt["sourceKind"];

/** Presentational filter over the loaded vault pages (the server lists the caller's receipts). */
export function filterReceipts(receipts: readonly Receipt[], kind: KindFilter): Receipt[] {
    return kind === "ALL" ? [...receipts] : receipts.filter((r) => r.sourceKind === kind);
}
