package com.couplefinance.support;

import com.couplefinance.ai.api.DocumentIntelligencePort;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real PostgreSQL and S3-compatible storage for tests (CLAUDE.md §8: no in-memory substitute; architecture.md §8:
 * Testcontainers PostgreSQL, MinIO). Same images as infra/docker-compose.yml. The containers are beans, so they are
 * shared by every test class that uses the same cached Spring context. Malware scanning uses
 * {@link TestMalwareScanner} (the ClamAV adapter has its own protocol contract test).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:17-alpine");
    /** Same pinned MinIO build as infra/docker-compose.yml (MinIO no longer publishes images on Docker Hub). */
    static final DockerImageName MINIO_IMAGE = DockerImageName.parse("bitnamilegacy/minio:2025.7.23");
    static final String MINIO_USER = "couplefinance-test";
    static final String MINIO_PASSWORD = "couplefinance-test-secret";
    /** Test-only KMS key: MinIO needs one to honour server-side encryption (SSE-S3) requests. */
    static final String MINIO_KMS_KEY = "couplefinance-test:3tC4DHJwJ9gcz9MpsJrQBO6rQ+oZTfSr7fHxyOV0EqY=";
    public static final String RECEIPT_BUCKET = "couplefinance-receipts-test";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }

    @Bean
    @SuppressWarnings("resource") // lifecycle managed by the Spring context
    GenericContainer<?> minioContainer() {
        return new GenericContainer<>(MINIO_IMAGE)
                .withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withEnv("MINIO_KMS_SECRET_KEY", MINIO_KMS_KEY)
                .waitingFor(Wait.forHttp("/minio/health/live").forPort(9000));
    }

    @Bean
    DynamicPropertyRegistrar receiptStorageProperties(GenericContainer<?> minioContainer) {
        return registry -> {
            registry.add("couplefinance.receipt.storage.endpoint",
                    () -> "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000));
            registry.add("couplefinance.receipt.storage.region", () -> "us-east-1");
            registry.add("couplefinance.receipt.storage.bucket", () -> RECEIPT_BUCKET);
            registry.add("couplefinance.receipt.storage.access-key", () -> MINIO_USER);
            registry.add("couplefinance.receipt.storage.secret-key", () -> MINIO_PASSWORD);
            registry.add("couplefinance.receipt.storage.path-style", () -> "true");
            registry.add("couplefinance.receipt.storage.create-bucket", () -> "true");
        };
    }

    @Bean
    @Primary
    TestMalwareScanner testMalwareScanner() {
        return new TestMalwareScanner();
    }

    /** Wraps the configured (fake) provider port so tests can script provider failures. */
    @Bean
    @Primary
    TestDocumentIntelligence testDocumentIntelligence(
            @Qualifier("documentIntelligencePort") DocumentIntelligencePort configured) {
        return new TestDocumentIntelligence(configured);
    }
}
