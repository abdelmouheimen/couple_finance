import type { components } from "@/shared/api/client";
import { api } from "@/shared/api/instance";
import { unwrap } from "@/shared/api/unwrap";

export type AiConsent = components["schemas"]["AiConsent"];
export type AiConsentPurpose = AiConsent["purpose"];

export const AI_CONSENTS_KEY = ["ai-consents"] as const;

/**
 * Version of the receipt-analysis disclosure shown by the privacy screen (`strings.privacy.receiptAnalysis`). It is
 * recorded with every decision (BR-AI-05): bump it whenever that wording changes.
 */
export const RECEIPT_ANALYSIS_CONSENT_VERSION = "receipt-analysis-v1";

export async function listAiConsents(): Promise<AiConsent[]> {
    return (await unwrap(api.GET("/api/v1/me/ai-consents"))).items;
}

export function decideAiConsent(
    purpose: AiConsentPurpose,
    granted: boolean,
    consentTextVersion: string,
): Promise<AiConsent> {
    return unwrap(
        api.PUT("/api/v1/me/ai-consents/{purpose}", {
            params: { path: { purpose } },
            body: { granted, consentTextVersion },
        }),
    );
}

/** Consent is never inferred: anything but an explicit grant is "not granted". */
export function isGranted(consents: readonly AiConsent[] | undefined, purpose: AiConsentPurpose) {
    return consents?.some((c) => c.purpose === purpose && c.granted) ?? false;
}
