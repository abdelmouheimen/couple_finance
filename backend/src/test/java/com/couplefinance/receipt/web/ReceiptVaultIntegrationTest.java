package com.couplefinance.receipt.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.couplefinance.receipt.ReceiptFiles;
import com.couplefinance.support.IntegrationTest;
import com.couplefinance.support.TestMalwareScanner;
import com.couplefinance.support.TestTokens;
import com.couplefinance.support.TestUsers;
import com.couplefinance.support.TestcontainersConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DOC-001 — secure receipt storage and the receipt vault, against PostgreSQL and MinIO (BR-RCP-01, BR-RCP-16,
 * BR-HH-10, security.md §6).
 */
@IntegrationTest
class ReceiptVaultIntegrationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestTokens tokens;

    @Autowired
    TestUsers users;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper jsonMapper;

    @Autowired
    S3Client s3;

    @Autowired
    TestMalwareScanner scanner;

    @AfterEach
    void resetScanner() {
        scanner.reset();
    }

    @Test
    void BR_RCP_01_an_uploaded_receipt_is_stored_encrypted_without_metadata_and_audited() throws Exception {
        UUID user = users.active();
        UUID household = createHousehold(user);

        MvcTestResult result = upload(user, null, file(ReceiptFiles.jpegWithExif(400, 700)));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        JsonNode json = json(result);
        UUID id = UUID.fromString(json.get("id").asString());
        assertThat(json.get("state").asString()).isEqualTo("UPLOADED");
        assertThat(json.get("sourceKind").asString()).isEqualTo("IMAGES");
        assertThat(json.get("pages")).hasSize(1);
        assertThat(json.toString()).doesNotContain("receipts/").doesNotContain("storageKey");

        var row = jdbc.sql("SELECT household_id, uploaded_by, status, analysis_status, ai_processed "
                        + "FROM receipt.receipt WHERE id = :id").param("id", id).query().singleRow();
        assertThat(row.get("household_id")).isEqualTo(household);
        assertThat(row.get("uploaded_by")).isEqualTo(user);
        assertThat(row.get("status")).isEqualTo("OPEN");
        assertThat(row.get("analysis_status")).isEqualTo("SKIPPED_NO_CONSENT");
        assertThat(row.get("ai_processed")).isEqualTo(false);

        String key = storageKey(id, 1);
        assertThat(key).startsWith("receipts/").doesNotContain(user.toString()).doesNotContain(household.toString());
        var head = s3.headObject(HeadObjectRequest.builder().bucket(TestcontainersConfiguration.RECEIPT_BUCKET)
                .key(key).build());
        assertThat(head.serverSideEncryption()).isEqualTo(ServerSideEncryption.AES256);
        assertThat(head.contentType()).isEqualTo("image/jpeg");

        byte[] stored = download(pageUrl(user, id, 1));
        assertThat(ReceiptFiles.contains(stored, ReceiptFiles.GPS_MARKER)).isFalse();
        assertThat(ReceiptFiles.contains(stored, "Exif")).isFalse();

        assertThat(auditActions(id)).containsExactly("UPLOAD");
    }

    @Test
    void BR_RCP_01_a_pdf_is_stored_as_rasterised_pages() {
        UUID user = users.active();
        createHousehold(user);

        MvcTestResult result = upload(user, null, file(ReceiptFiles.pdf(2)));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        JsonNode json = json(result);
        assertThat(json.get("sourceKind").asString()).isEqualTo("PDF");
        assertThat(json.get("pages")).hasSize(2)
                .allSatisfy(page -> assertThat(page.get("contentType").asString()).isEqualTo("image/jpeg"));
        UUID id = UUID.fromString(json.get("id").asString());
        assertThat(jdbc.sql("SELECT count(DISTINCT original_sha256) FROM receipt.receipt_page WHERE receipt_id = :id")
                .param("id", id).query(Long.class).single()).isEqualTo(1L);
    }

    @Test
    void BR_RCP_01_invalid_uploads_are_rejected_and_nothing_is_stored() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        long objectsBefore = objectCount();

        assertThat(upload(user, null, new MockMultipartFile("files", "receipt.jpg", "image/jpeg",
                "MZ not an image".getBytes()))).hasStatus(HttpStatus.UNSUPPORTED_MEDIA_TYPE).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_FORMAT_UNSUPPORTED");
        assertThat(upload(user, null, file(ReceiptFiles.pdf(6)))).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_PDF_PAGE_COUNT_INVALID");
        assertThat(upload(user, null, file(ReceiptFiles.encryptedPdf()))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("RECEIPT_FILE_UNREADABLE");
        MockMultipartFile png = file(ReceiptFiles.png(10, 10));
        assertThat(upload(user, null, png, png, png, png, png, png)).hasStatus(HttpStatus.BAD_REQUEST).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_FILE_COUNT_INVALID");
        assertThat(upload(user, null, png, file(ReceiptFiles.pdf(1)))).hasStatus(HttpStatus.BAD_REQUEST)
                .bodyJson().extractingPath("$.code").isEqualTo("RECEIPT_FORMAT_MIXED");

        assertThat(receiptCount(household)).isZero();
        assertThat(objectCount()).isEqualTo(objectsBefore);
    }

    @Test
    void an_infected_file_is_rejected_before_anything_is_decoded_or_stored() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        long objectsBefore = objectCount();
        byte[] infected = ReceiptFiles.jpeg(50, 50);
        byte[] withEicar = new byte[infected.length + TestMalwareScanner.EICAR.length()];
        System.arraycopy(infected, 0, withEicar, 0, infected.length);
        System.arraycopy(TestMalwareScanner.EICAR.getBytes(), 0, withEicar, infected.length,
                TestMalwareScanner.EICAR.length());

        assertThat(upload(user, null, file(withEicar))).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson()
                .extractingPath("$.code").isEqualTo("RECEIPT_FILE_REJECTED");
        assertThat(receiptCount(household)).isZero();
        assertThat(objectCount()).isEqualTo(objectsBefore);
    }

    @Test
    void uploads_fail_closed_when_the_scanner_is_unavailable() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        scanner.simulateOutage();

        assertThat(upload(user, null, file(ReceiptFiles.jpeg(50, 50)))).hasStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .bodyJson().extractingPath("$.code").isEqualTo("RECEIPT_SCAN_UNAVAILABLE");
        assertThat(receiptCount(household)).isZero();
    }

    @Test
    void BR_EXP_13_a_retried_upload_with_the_same_idempotency_key_creates_one_receipt() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        MockMultipartFile jpeg = file(ReceiptFiles.jpeg(80, 120));

        MvcTestResult first = upload(user, "upload-1", jpeg);
        MvcTestResult retry = upload(user, "upload-1", jpeg);

        assertThat(first).hasStatus(HttpStatus.CREATED);
        assertThat(retry).hasStatus(HttpStatus.CREATED);
        assertThat(json(retry).get("id").asString()).isEqualTo(json(first).get("id").asString());
        assertThat(receiptCount(household)).isEqualTo(1L);
        assertThat(upload(user, "upload-1", file(ReceiptFiles.png(20, 20))))
                .hasStatus(HttpStatus.UNPROCESSABLE_CONTENT).bodyJson().extractingPath("$.code")
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void the_vault_lists_reads_paginates_and_serves_short_lived_page_urls() throws Exception {
        UUID user = users.active();
        createHousehold(user);
        UUID first = id(upload(user, null, file(ReceiptFiles.jpeg(60, 90))));
        UUID second = id(upload(user, null, file(ReceiptFiles.png(30, 30))));

        JsonNode page1 = json(mvc.get().uri("/api/v1/receipts?limit=1").header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(user)).exchange());
        assertThat(page1.get("items")).singleElement()
                .satisfies(item -> assertThat(item.get("id").asString()).isEqualTo(second.toString()));
        String cursor = page1.get("nextCursor").asString();
        JsonNode page2 = json(mvc.get().uri("/api/v1/receipts?limit=1&cursor=" + cursor)
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange());
        assertThat(page2.get("items")).singleElement()
                .satisfies(item -> assertThat(item.get("id").asString()).isEqualTo(first.toString()));
        assertThat(page2.has("nextCursor") && !page2.get("nextCursor").isNull()).isFalse();

        assertThat(get(user, first)).hasStatus(HttpStatus.OK);
        MvcTestResult url = mvc.get().uri("/api/v1/receipts/" + first + "/pages/1/url")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange();
        assertThat(url).hasStatus(HttpStatus.OK);
        assertThat(url.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        JsonNode body = json(url);
        OffsetDateTime expires = OffsetDateTime.parse(body.get("expiresAt").asString());
        assertThat(expires).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(6));
        assertThat(body.get("url").asString()).contains("X-Amz-Expires=300").contains("X-Amz-Signature");
        assertThat(download(body.get("url").asString())).isNotEmpty();
        assertThat(mvc.get().uri("/api/v1/receipts/" + first + "/pages/2/url")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange())
                .hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("RECEIPT_PAGE_NOT_FOUND");
    }

    @Test
    void deleting_an_open_receipt_removes_rows_and_objects_and_is_audited() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = id(upload(user, null, file(ReceiptFiles.jpeg(60, 90))));
        String key = storageKey(id, 1);

        assertThat(delete(user, id)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(get(user, id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.sql("SELECT count(*) FROM receipt.receipt_page WHERE receipt_id = :id").param("id", id)
                .query(Long.class).single()).isZero();
        assertThat(objectExists(key)).isFalse();
        assertThat(auditActions(id)).containsExactly("UPLOAD", "DELETE");
        assertThat(delete(user, id)).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void BR_RCP_20_a_linked_receipt_cannot_be_deleted_from_the_vault() {
        UUID user = users.active();
        createHousehold(user);
        UUID id = id(upload(user, null, file(ReceiptFiles.jpeg(60, 90))));
        // Linking arrives with confirmation (DOC-004); the vault must already refuse to delete linked evidence.
        jdbc.sql("UPDATE receipt.receipt SET status = 'ATTACHED', linked_at = now() WHERE id = :id").param("id", id)
                .update();

        // Until DOC-004 a linked receipt follows the visibility of its expense, which the vault cannot resolve yet.
        assertThat(get(user, id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(user, id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.sql("SELECT count(*) FROM receipt.receipt WHERE id = :id").param("id", id).query(Long.class)
                .single()).isEqualTo(1L);
    }

    @Test
    void BR_RCP_16_a_partner_never_sees_reads_or_deletes_the_other_members_draft() {
        UUID owner = users.active();
        UUID household = createHousehold(owner);
        UUID partner = users.active();
        addMember(household, partner);
        UUID id = id(upload(owner, null, file(ReceiptFiles.jpeg(60, 90))));

        JsonNode list = json(mvc.get().uri("/api/v1/receipts").header(HttpHeaders.AUTHORIZATION,
                tokens.bearer(partner)).exchange());
        assertThat(list.get("items")).isEmpty();
        assertThat(get(partner, id)).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code")
                .isEqualTo("RECEIPT_NOT_FOUND");
        assertThat(mvc.get().uri("/api/v1/receipts/" + id + "/pages/1/url")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(partner)).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(partner, id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(owner, id)).hasStatus(HttpStatus.OK);
    }

    @Test
    void another_household_gets_404_and_unauthenticated_requests_401() {
        UUID owner = users.active();
        createHousehold(owner);
        UUID id = id(upload(owner, null, file(ReceiptFiles.jpeg(60, 90))));
        UUID stranger = users.active();
        createHousehold(stranger);

        assertThat(get(stranger, id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(delete(stranger, id)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(mvc.get().uri("/api/v1/receipts/" + id + "/pages/1/url")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(stranger)).exchange())
                .hasStatus(HttpStatus.NOT_FOUND);

        assertThat(mvc.get().uri("/api/v1/receipts").exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.get().uri("/api/v1/receipts/" + id).exchange()).hasStatus(HttpStatus.UNAUTHORIZED);
        assertThat(mvc.post().uri("/api/v1/receipts").multipart().file(file(ReceiptFiles.jpeg(10, 10))).exchange())
                .hasStatus(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void BR_HH_10_uploads_and_deletions_are_rejected_on_a_dissolved_household() {
        UUID user = users.active();
        UUID household = createHousehold(user);
        UUID id = id(upload(user, null, file(ReceiptFiles.jpeg(60, 90))));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("UPDATE household.household SET status = 'DISSOLVED', dissolved_at = :now, "
                + "purge_at = :now + interval '90 days' WHERE id = :id").param("now", now).param("id", household)
                .update();
        jdbc.sql("UPDATE household.household_member SET left_at = :now, archive_access_until = :until "
                + "WHERE household_id = :id AND user_id = :user").param("now", now)
                .param("until", now.plusDays(30)).param("id", household).param("user", user).update();

        assertThat(upload(user, null, file(ReceiptFiles.jpeg(10, 10)))).hasStatus(HttpStatus.FORBIDDEN).bodyJson()
                .extractingPath("$.code").isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(delete(user, id)).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code")
                .isEqualTo("HOUSEHOLD_READ_ONLY");
        assertThat(get(user, id)).hasStatus(HttpStatus.OK);
    }

    // ------------------------------------------------------------------ helpers

    private static MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("files", "receipt", "application/octet-stream", bytes);
    }

    private MvcTestResult upload(UUID user, String idempotencyKey, MockMultipartFile... files) {
        var request = mvc.post().uri("/api/v1/receipts").multipart();
        for (MockMultipartFile file : files) {
            request = request.file(file);
        }
        request = request.header(HttpHeaders.AUTHORIZATION, tokens.bearer(user));
        if (idempotencyKey != null) {
            request = request.header("Idempotency-Key", idempotencyKey);
        }
        return request.exchange();
    }

    private MvcTestResult get(UUID user, UUID id) {
        return mvc.get().uri("/api/v1/receipts/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    private MvcTestResult delete(UUID user, UUID id) {
        return mvc.delete().uri("/api/v1/receipts/" + id).header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .exchange();
    }

    private String pageUrl(UUID user, UUID id, int position) {
        return json(mvc.get().uri("/api/v1/receipts/" + id + "/pages/" + position + "/url")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user)).exchange()).get("url").asString();
    }

    private static byte[] download(String url) throws Exception {
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(url))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private String storageKey(UUID receipt, int position) {
        return jdbc.sql("SELECT storage_key FROM receipt.receipt_page WHERE receipt_id = :id AND position = :p")
                .param("id", receipt).param("p", position).query(String.class).single();
    }

    private boolean objectExists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(TestcontainersConfiguration.RECEIPT_BUCKET).key(key)
                    .build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    private long objectCount() {
        return s3.listObjectsV2Paginator(b -> b.bucket(TestcontainersConfiguration.RECEIPT_BUCKET)).contents()
                .stream().count();
    }

    private long receiptCount(UUID household) {
        return jdbc.sql("SELECT count(*) FROM receipt.receipt WHERE household_id = :h").param("h", household)
                .query(Long.class).single();
    }

    private List<String> auditActions(UUID receipt) {
        return jdbc.sql("SELECT action FROM receipt.audit_event WHERE entity_id = :id ORDER BY occurred_at, id")
                .param("id", receipt).query(String.class).list();
    }

    private UUID createHousehold(UUID user) {
        MvcTestResult result = mvc.post().uri("/api/v1/households")
                .header(HttpHeaders.AUTHORIZATION, tokens.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"Foyer\"}").exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private void addMember(UUID household, UUID user) {
        jdbc.sql("INSERT INTO household.household_member (household_id, user_id, seat, joined_at) "
                + "VALUES (:household, :user, 2, now())").param("household", household).param("user", user).update();
    }

    private UUID id(MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json(result).get("id").asString());
    }

    private JsonNode json(MvcTestResult result) {
        return jsonMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
