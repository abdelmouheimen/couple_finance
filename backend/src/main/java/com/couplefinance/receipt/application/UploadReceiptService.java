package com.couplefinance.receipt.application;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.couplefinance.household.api.CurrentHousehold;
import com.couplefinance.household.api.HouseholdContext;
import com.couplefinance.receipt.domain.Receipt;
import com.couplefinance.receipt.domain.ReceiptAuditLog;
import com.couplefinance.receipt.domain.ReceiptErrorCode;
import com.couplefinance.receipt.domain.ReceiptPage;
import com.couplefinance.receipt.domain.ReceiptRepository;
import com.couplefinance.receipt.domain.UploadPolicy;
import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.UuidV7;
import com.couplefinance.shared.idempotency.IdempotencyKey;
import com.couplefinance.shared.idempotency.IdempotencyService;
import com.couplefinance.shared.idempotency.IdempotentResponse;
import com.couplefinance.shared.idempotency.IdempotentResult;
import com.couplefinance.shared.idempotency.RequestHash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upload use case (F4, BR-RCP-01, architecture.md section 6.2): policy check → malware scan (fail-closed) → safe
 * re-encoding → hashes → object storage → one database transaction (receipt, pages, audit). Storage and scanning are
 * network I/O and run outside any transaction; if the transaction fails, the objects already stored are deleted.
 * Deliberately not {@code @Transactional}: the idempotency claim commits independently of the upload.
 */
@Service
public class UploadReceiptService {

    static final String OPERATION = "receipt.upload";
    private static final Logger log = LoggerFactory.getLogger(UploadReceiptService.class);

    private final CurrentHousehold currentHousehold;
    private final MalwareScanner scanner;
    private final PageNormaliser normaliser;
    private final DocumentStorage storage;
    private final ReceiptRepository receipts;
    private final ReceiptAuditLog audit;
    private final IdempotencyService idempotency;
    private final TransactionTemplate transaction;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    UploadReceiptService(CurrentHousehold currentHousehold, MalwareScanner scanner, PageNormaliser normaliser,
            DocumentStorage storage, ReceiptRepository receipts, ReceiptAuditLog audit,
            IdempotencyService idempotency, TransactionTemplate transaction, JsonMapper jsonMapper, Clock clock) {
        this.currentHousehold = currentHousehold;
        this.scanner = scanner;
        this.normaliser = normaliser;
        this.storage = storage;
        this.receipts = receipts;
        this.audit = audit;
        this.idempotency = idempotency;
        this.transaction = transaction;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    /** The created receipt and whether it is a replay of an earlier identical upload (same Idempotency-Key). */
    public record Uploaded(ReceiptView receipt, boolean replayed) {
    }

    public Uploaded upload(List<UploadPolicy.UploadedFile> files, Optional<IdempotencyKey> key) {
        HouseholdContext context = currentHousehold.currentHousehold();
        context.requireWritable();
        UploadPolicy.Checked checked = UploadPolicy.check(files);
        if (key.isEmpty()) {
            return new Uploaded(store(context, checked), false);
        }
        IdempotentResult result = idempotency.execute(context.userId().value(), key.get(), OPERATION,
                hash(checked), () -> new IdempotentResponse(HttpStatus.CREATED.value(),
                        jsonMapper.writeValueAsString(store(context, checked))));
        return new Uploaded(jsonMapper.readValue(result.response().jsonBody(), ReceiptView.class),
                result.replayed());
    }

    private ReceiptView store(HouseholdContext context, UploadPolicy.Checked checked) {
        for (UploadPolicy.CheckedFile file : checked.files()) {
            scan(file.bytes());
        }
        List<Prepared> prepared = new ArrayList<>();
        for (UploadPolicy.CheckedFile file : checked.files()) {
            byte[] sha256 = sha256(file.bytes());
            for (PageNormaliser.NormalisedPage page : normaliser.normalise(file.format(), file.bytes())) {
                prepared.add(new Prepared(page, sha256));
            }
        }
        if (prepared.size() > UploadPolicy.MAX_PDF_PAGES) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_PDF_PAGE_COUNT_INVALID,
                    "A receipt has at most " + UploadPolicy.MAX_PDF_PAGES + " pages.");
        }
        UUID receiptId = UuidV7.generate(clock);
        List<ReceiptPage> pages = new ArrayList<>();
        List<String> stored = new ArrayList<>();
        try {
            for (int i = 0; i < prepared.size(); i++) {
                Prepared p = prepared.get(i);
                String storageKey = "receipts/" + UUID.randomUUID();
                storage.put(storageKey, p.page().content(), p.page().contentType());
                stored.add(storageKey);
                pages.add(new ReceiptPage(UuidV7.generate(clock), i + 1, storageKey, p.page().contentType(),
                        p.page().content().length, p.page().widthPx(), p.page().heightPx(), p.sha256(),
                        p.page().perceptualHash()));
            }
            Receipt receipt = Receipt.uploaded(receiptId, context.householdId(), context.userId(), checked.kind(),
                    checked.totalBytes(), pages, clock.instant());
            transaction.executeWithoutResult(status -> {
                receipts.insert(receipt);
                audit.uploaded(receipt, context.userId());
            });
            log.info("Receipt uploaded: receipt={} pages={}", receiptId, pages.size());
            return ReceiptView.from(receipt);
        } catch (RuntimeException e) {
            stored.forEach(this::deleteQuietly);
            throw e;
        }
    }

    private void scan(byte[] content) {
        MalwareScanner.Verdict verdict;
        try {
            verdict = scanner.scan(content);
        } catch (MalwareScanner.ScannerUnavailableException e) {
            log.warn("Malware scanner unavailable: upload refused ({})", e.getMessage());
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_SCAN_UNAVAILABLE,
                    "Uploads are temporarily unavailable. Please try again later.", e);
        }
        if (verdict == MalwareScanner.Verdict.INFECTED) {
            log.warn("Receipt upload rejected by the malware scanner");
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_FILE_REJECTED, "This file was rejected.");
        }
    }

    private void deleteQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException e) {
            log.warn("Could not delete an orphan receipt object after a failed upload ({})",
                    e.getClass().getSimpleName());
        }
    }

    private record Prepared(PageNormaliser.NormalisedPage page, byte[] sha256) {
    }

    private static byte[] hash(UploadPolicy.Checked checked) {
        RequestHash.Builder hash = RequestHash.forOperation(OPERATION)
                .field("fileCount", Integer.toString(checked.files().size()));
        for (int i = 0; i < checked.files().size(); i++) {
            hash.field("file." + i + ".sha256", HexFormat.of().formatHex(sha256(checked.files().get(i).bytes())));
        }
        return hash.build();
    }

    static byte[] sha256(byte[] content) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(content);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JVM specification", e);
        }
    }
}
