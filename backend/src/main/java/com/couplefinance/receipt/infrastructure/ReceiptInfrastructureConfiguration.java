package com.couplefinance.receipt.infrastructure;

import com.couplefinance.receipt.application.DocumentStorage;
import com.couplefinance.receipt.application.MalwareScanner;
import com.couplefinance.receipt.application.PageNormaliser;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/** Wires the receipt module's outbound adapters (hexagonal: the application only sees the ports). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReceiptStorageProperties.class)
class ReceiptInfrastructureConfiguration {

    @Bean(destroyMethod = "close")
    S3Client receiptS3Client(ReceiptStorageProperties properties) {
        ReceiptStorageProperties.Storage storage = properties.storage();
        var builder = S3Client.builder()
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .httpClient(UrlConnectionHttpClient.create())
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(storage.pathStyle()).build());
        if (storage.endpoint() != null) {
            builder.endpointOverride(storage.endpoint());
        }
        S3Client client = builder.build();
        if (storage.createBucket()) {
            createBucketIfMissing(client, storage.bucket());
        }
        return client;
    }

    @Bean(destroyMethod = "close")
    S3Presigner receiptS3Presigner(ReceiptStorageProperties properties) {
        ReceiptStorageProperties.Storage storage = properties.storage();
        var builder = S3Presigner.builder()
                .region(Region.of(storage.region()))
                .credentialsProvider(credentials(storage))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(storage.pathStyle()).build());
        var endpoint = storage.publicEndpoint() != null ? storage.publicEndpoint() : storage.endpoint();
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        return builder.build();
    }

    @Bean
    DocumentStorage receiptDocumentStorage(S3Client client, S3Presigner presigner,
            ReceiptStorageProperties properties) {
        return new S3DocumentStorage(client, presigner, properties.storage());
    }

    @Bean
    MalwareScanner receiptMalwareScanner(ReceiptStorageProperties properties) {
        return new ClamAvMalwareScanner(properties.scanner());
    }

    @Bean
    PageNormaliser receiptPageNormaliser() {
        return new JavaPageNormaliser();
    }

    private static AwsCredentialsProvider credentials(ReceiptStorageProperties.Storage storage) {
        if (storage.accessKey() != null && !storage.accessKey().isBlank() && storage.secretKey() != null) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(storage.accessKey(),
                    storage.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    /** Local development and tests only. A storage outage must not prevent the rest of the API from starting. */
    private static void createBucketIfMissing(S3Client client, String bucket) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
        } catch (NoSuchBucketException e) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
        } catch (SdkException e) {
            LoggerFactory.getLogger(ReceiptInfrastructureConfiguration.class)
                    .warn("Receipt storage unreachable at start-up; uploads will fail until it is available ({})",
                            e.getClass().getSimpleName());
        }
    }
}
