package com.couplefinance.receipt.domain;

/** Non-blocking warnings attached to a resolved value. */
public enum ParseWarning {
    /** BR-RCP-08: the date is older than one year. */
    STALE,
    /** BR-RCP-09: the detected currency differs from the household currency. */
    CURRENCY_DIFFERS_FROM_HOUSEHOLD
}
