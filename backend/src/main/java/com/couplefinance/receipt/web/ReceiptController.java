package com.couplefinance.receipt.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.UUID;

import com.couplefinance.receipt.application.ReceiptVaultService;
import com.couplefinance.receipt.application.UploadReceiptService;
import com.couplefinance.receipt.domain.UploadPolicy;
import com.couplefinance.shared.error.ProblemSchema;
import com.couplefinance.shared.idempotency.IdempotencyKey;
import com.couplefinance.shared.pagination.PageQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The receipt vault (F4, DOC-001): upload, list, read, page URLs, delete. */
@RestController
@RequestMapping("/api/v1/receipts")
@Tag(name = "Receipts")
class ReceiptController {

    private static final String PROBLEM = "application/problem+json";

    private final UploadReceiptService upload;
    private final ReceiptVaultService vault;

    ReceiptController(UploadReceiptService upload, ReceiptVaultService vault) {
        this.upload = upload;
        this.vault = vault;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(operationId = "uploadReceipt", summary = "Upload a receipt",
            description = "Stores 1-5 images (JPEG, PNG) or one PDF of at most 5 pages as a new private receipt of "
                    + "the caller (BR-RCP-01, BR-RCP-16): file types are checked from the bytes, files are scanned "
                    + "for malware, images are re-encoded without metadata and PDFs rasterised. Limits: 10 MB per "
                    + "file, 25 MB in total. HEIC must be converted to JPEG by the client. Nothing is sent to an AI "
                    + "provider. Send an Idempotency-Key to retry safely (same key and files: original response).")
    @ApiResponse(responseCode = "201", description = "Receipt stored")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, RECEIPT_FILE_COUNT_INVALID, "
            + "RECEIPT_FORMAT_MIXED, RECEIPT_PDF_PAGE_COUNT_INVALID, RECEIPT_FILE_UNREADABLE or IDEMPOTENCY_KEY_INVALID",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "REQUEST_IN_PROGRESS",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "413", description = "RECEIPT_FILE_TOO_LARGE, RECEIPT_TOO_LARGE or PAYLOAD_TOO_LARGE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "415", description = "RECEIPT_FORMAT_UNSUPPORTED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "422", description = "RECEIPT_FILE_REJECTED (malware) or IDEMPOTENCY_KEY_REUSED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "503", description = "RECEIPT_SCAN_UNAVAILABLE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<ReceiptResponse> upload(
            @Parameter(description = "Client-generated key (1-100 printable ASCII characters).")
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Parameter(description = "1-5 images or one PDF, in page order.")
            @RequestPart("files") List<MultipartFile> files) {
        List<UploadPolicy.UploadedFile> uploaded = files.stream().map(ReceiptController::read).toList();
        UploadReceiptService.Uploaded result = upload.upload(uploaded, IdempotencyKey.fromHeader(idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(ReceiptResponse.from(result.receipt()));
    }

    @GetMapping
    @Operation(operationId = "listReceipts", summary = "List the receipt vault",
            description = "The caller's receipts, newest first, cursor-paginated. An open receipt is visible only "
                    + "to its uploader (BR-RCP-16): the partner's drafts never appear.")
    @ApiResponse(responseCode = "200", description = "A page of receipts")
    @ApiResponse(responseCode = "400", description = "INVALID_CURSOR or INVALID_LIMIT",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    ReceiptPageResponse list(
            @Parameter(description = "Page size, default 20, capped at 100.")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "Opaque cursor of the previous page.")
            @RequestParam(required = false) String cursor) {
        return ReceiptPageResponse.from(vault.list(PageQuery.of(limit, cursor)));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getReceipt", summary = "Read a receipt",
            description = "Metadata of a visible receipt. Unknown, other household and partner drafts are 404.")
    @ApiResponse(responseCode = "200", description = "The receipt")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or RECEIPT_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    ReceiptResponse get(@PathVariable UUID id) {
        return ReceiptResponse.from(vault.get(id));
    }

    @GetMapping("/{id}/pages/{position}/url")
    @Operation(operationId = "getReceiptPageUrl", summary = "Get a page preview/download URL",
            description = "A presigned GET URL of one stored page (re-encoded JPEG), valid 5 minutes, issued after "
                    + "the household and visibility checks. The response is never cached.")
    @ApiResponse(responseCode = "200", description = "The page URL")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND, RECEIPT_NOT_FOUND or "
            + "RECEIPT_PAGE_NOT_FOUND", content = @Content(mediaType = PROBLEM,
            schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<ReceiptPageUrlResponse> pageUrl(@PathVariable UUID id, @PathVariable int position) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(ReceiptPageUrlResponse.from(vault.pageUrl(id, position)));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteReceipt", summary = "Delete a receipt",
            description = "Deletes one of the caller's open receipts and its stored pages. The deletion is audited. "
                    + "A receipt linked to an expense cannot be deleted here (409). Rejected on a dissolved "
                    + "household.")
    @ApiResponse(responseCode = "204", description = "Receipt deleted")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or RECEIPT_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "RECEIPT_NOT_DELETABLE",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        vault.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static UploadPolicy.UploadedFile read(MultipartFile file) {
        try {
            return new UploadPolicy.UploadedFile(file.getBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
