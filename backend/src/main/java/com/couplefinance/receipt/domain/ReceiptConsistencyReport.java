package com.couplefinance.receipt.domain;

import java.util.Set;

/**
 * Result of the deterministic consistency checks. {@code taxLinesStatus} is informational. Nothing here carries
 * a modified value.
 */
public record ReceiptConsistencyReport(
        ReceiptFieldStatus totalStatus,
        ReceiptFieldStatus taxLinesStatus,
        Set<ConsistencyFinding> findings,
        ProposedKind proposedKind) {

    public ReceiptConsistencyReport {
        findings = Set.copyOf(findings);
    }
}
