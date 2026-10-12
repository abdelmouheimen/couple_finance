package com.couplefinance.receipt.infrastructure;

import java.net.URI;
import java.time.Duration;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Receipt document storage and scanning (DOC-001, security.md section 6). Credentials come from the environment
 * (never committed): {@code COUPLEFINANCE_RECEIPT_STORAGE_ACCESS_KEY} / {@code ..._SECRET_KEY}; without them the
 * default AWS credential chain applies.
 *
 * @param storage S3-compatible private bucket
 * @param scanner ClamAV daemon reached over TCP
 */
@ConfigurationProperties("couplefinance.receipt")
public record ReceiptStorageProperties(Storage storage, Scanner scanner) {

    /**
     * @param endpoint       S3 API endpoint used by the server; {@code null} = AWS default for the region
     * @param publicEndpoint endpoint written into presigned URLs (what the phone can reach); defaults to endpoint
     * @param encryption     server-side encryption requested on every object: {@code AES256} (SSE-S3) or
     *                       {@code aws:kms} with {@code kmsKeyId}
     * @param createBucket   create the bucket at start-up when missing (local development and tests only)
     */
    public record Storage(@Nullable URI endpoint, @Nullable URI publicEndpoint, String region, String bucket,
            @Nullable String accessKey, @Nullable String secretKey, boolean pathStyle, String encryption,
            @Nullable String kmsKeyId, boolean createBucket) {
    }

    /** @param timeout connect and read timeout of one scan */
    public record Scanner(String host, int port, Duration timeout) {
    }
}
