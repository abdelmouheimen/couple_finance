package com.couplefinance.receipt.domain;

/** Why a transcribed value is {@code INVALID} (BR-RCP-04). */
public enum InvalidReason {
    UNPARSABLE,
    TOO_LONG,
    /** BR-MON-07: every reading exceeds the per-currency maximum. */
    OUT_OF_RANGE,
    /** BR-MON-03: every reading has more decimals than the currency allows (never rounded). */
    TOO_MANY_DECIMALS,
    /** BR-RCP-04: the sign is not allowed for the line-item type. */
    INVALID_SIGN,
    INVALID_DATE,
    /** BR-RCP-08: every calendar-valid reading is in the future (> 1 day) or older than 5 years. */
    DATE_OUT_OF_BOUNDS,
    UNKNOWN_CURRENCY
}
