package com.couplefinance.receipt.domain;

/** BR-RCP-12: the document type classified by the model (untrusted input). */
public enum DocumentType {
    RECEIPT,
    CARD_SLIP,
    INVOICE,
    QUOTE,
    REFUND_RECEIPT,
    OTHER
}
