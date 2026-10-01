package com.couplefinance.receipt.domain;

import java.util.List;
import java.util.Set;

/**
 * Outcome of deterministically parsing one value transcribed from a receipt (BR-RCP-04/05). The input comes from
 * untrusted AI output: a value is never rounded, clamped or corrected — it is resolved, ambiguous or invalid.
 */
public sealed interface ParseResult<T> {

    /** Exactly one reading, chosen because it was the only valid one or because all available hints agree. */
    record Resolved<T>(T value, Set<ParseWarning> warnings) implements ParseResult<T> {
        public Resolved {
            warnings = Set.copyOf(warnings);
        }
    }

    /** More than one valid reading and the hints do not single one out (BR-RCP-05): the user chooses. */
    record Ambiguous<T>(List<T> candidates) implements ParseResult<T> {
        public Ambiguous {
            candidates = List.copyOf(candidates);
            if (candidates.size() < 2) {
                throw new IllegalArgumentException("An ambiguous result needs at least two candidates.");
            }
        }
    }

    /** No acceptable reading. Carries only a reason code, never the raw receipt content. */
    record Invalid<T>(InvalidReason reason) implements ParseResult<T> {
    }
}
