package com.couplefinance.receipt.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Receipt analysis jobs (database-schema.md section 8.4).
 *
 * @param pollInterval delay between two worker polls
 * @param batchSize    jobs claimed per poll
 * @param lease        how long a claimed job stays owned by its worker before it is re-queued
 * @param retryBackoff base delay of the exponential backoff between automatic attempts (≤ 3)
 */
@ConfigurationProperties("couplefinance.receipt.analysis")
public record ReceiptAnalysisProperties(Duration pollInterval, int batchSize, Duration lease, Duration retryBackoff) {
}
