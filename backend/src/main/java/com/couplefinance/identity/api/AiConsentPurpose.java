package com.couplefinance.identity.api;

/** Purposes of AI processing a user consents to separately (ADR-005, BR-AI-05, BR-AI-09). */
public enum AiConsentPurpose {
    /** (a) Sending the user's receipts to an AI provider for transcription. */
    RECEIPT_ANALYSIS,
    /** (b) AI-worded insights and the assistant. */
    INSIGHTS_ASSISTANT,
    /** (c) Contributing receipts to the quality dataset (separate opt-in). */
    QUALITY_DATASET
}
