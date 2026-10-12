package com.couplefinance.receipt.infrastructure;

import java.net.URI;
import java.time.Duration;

import com.couplefinance.receipt.application.DocumentStorage;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

/**
 * S3-compatible adapter of {@link DocumentStorage}: private bucket, server-side encryption requested on every
 * object (the put fails if the store cannot encrypt), no public ACL, short-lived presigned GETs.
 */
class S3DocumentStorage implements DocumentStorage {

    private final S3Client client;
    private final S3Presigner presigner;
    private final ReceiptStorageProperties.Storage properties;

    S3DocumentStorage(S3Client client, S3Presigner presigner, ReceiptStorageProperties.Storage properties) {
        this.client = client;
        this.presigner = presigner;
        this.properties = properties;
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        PutObjectRequest.Builder request = PutObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .contentType(contentType)
                .contentLength((long) content.length)
                .cacheControl("private, no-store")
                .serverSideEncryption(ServerSideEncryption.fromValue(properties.encryption()));
        if (properties.kmsKeyId() != null && !properties.kmsKeyId().isBlank()) {
            request.ssekmsKeyId(properties.kmsKeyId());
        }
        client.putObject(request.build(), RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String key) {
        return client.getObjectAsBytes(GetObjectRequest.builder().bucket(properties.bucket()).key(key).build())
                .asByteArray();
    }

    @Override
    public URI presignedGet(String key, Duration validity) {
        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(properties.bucket())
                .key(key)
                .responseCacheControl("private, no-store")
                .build();
        return URI.create(presigner.presignGetObject(GetObjectPresignRequest.builder()
                .signatureDuration(validity)
                .getObjectRequest(get)
                .build()).url().toString());
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(properties.bucket()).key(key).build());
    }
}
