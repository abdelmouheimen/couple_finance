package com.couplefinance.receipt.domain;

/** Why a consistency check flagged the receipt. Codes only, never receipt content. */
public enum ConsistencyFinding {
    /** BR-RCP-07: signed line-item sum differs from the total beyond tolerance. */
    LINE_ITEMS_DO_NOT_SUM_TO_TOTAL,
    /** BR-RCP-06/07: EXCLUSIVE subtotal + tax differs from the total beyond tolerance. */
    SUBTOTAL_PLUS_TAX_DIFFERS_FROM_TOTAL,
    /** BR-RCP-07: payments minus change differ from the total beyond tolerance. */
    PAYMENTS_DO_NOT_MATCH_TOTAL,
    /** ai.md §4.4: INCLUSIVE tax lines differ from the rate-derived tax (informational). */
    TAX_DIFFERS_FROM_RATE_DERIVED,
    /** BR-RCP-13: handwritten total is at least the printed total (probable tip). */
    HANDWRITTEN_TOTAL_AT_LEAST_PRINTED,
    /** ai.md §4.4: handwritten total is below the printed total. */
    HANDWRITTEN_TOTAL_BELOW_PRINTED,
    /** BR-RCP-13: the receipt contains a TIP line; the user confirms the final amount. */
    TIP_LINE_PRESENT,
    /** BR-RCP-12: QUOTE/OTHER is not a proof of payment. */
    NOT_A_PROOF_OF_PAYMENT
}
