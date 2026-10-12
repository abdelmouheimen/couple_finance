package com.couplefinance.receipt.application;

import java.net.URI;
import java.time.Duration;

/**
 * Outbound port: private object storage for receipt pages (security.md section 6). Implementations store objects
 * with server-side encryption, never publicly readable; keys are random and carry no user or household data.
 * Calls are network I/O: never made while a database transaction is open (CLAUDE.md section 6.3).
 */
public interface DocumentStorage {

    void put(String key, byte[] content, String contentType);

    /** Reads a stored object (analysis of the re-encoded pages). */
    byte[] get(String key);

    /** A short-lived read URL. Never log it or send it to crash reporting. */
    URI presignedGet(String key, Duration validity);

    /** Idempotent: deleting a missing object succeeds. */
    void delete(String key);
}
