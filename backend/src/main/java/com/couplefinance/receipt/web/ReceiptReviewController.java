package com.couplefinance.receipt.web;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.couplefinance.receipt.application.ConfirmReceiptService;
import com.couplefinance.receipt.application.ReceiptReviewService;
import com.couplefinance.receipt.application.ReceiptReviewView;
import com.couplefinance.receipt.domain.ReceiptConfirmationPolicy;
import com.couplefinance.receipt.domain.ReceiptState;
import com.couplefinance.shared.error.ProblemSchema;
import com.couplefinance.shared.idempotency.IdempotencyKey;
import com.couplefinance.shared.money.Money;
import com.couplefinance.shared.money.MoneySchema;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Review and confirmation of a receipt (F5, DOC-004): proposals in, an expense only on explicit confirmation. */
@RestController
@RequestMapping("/api/v1/receipts/{id}")
@Tag(name = "Receipts")
class ReceiptReviewController {

    private static final String PROBLEM = "application/problem+json";
    /** Same bounds as a manual expense (expense module). */
    private static final int MERCHANT_MAX = 120;
    private static final int NOTE_MAX = 500;

    private final ReceiptReviewService review;
    private final ConfirmReceiptService confirm;

    ReceiptReviewController(ReceiptReviewService review, ConfirmReceiptService confirm) {
        this.review = review;
        this.confirm = confirm;
    }

    enum Kind { EXPENSE, REFUND }

    enum Sharing { SHARED, PERSONAL }

    @Schema(name = "ReceiptReviewField", requiredProperties = {"candidates", "status"})
    record Field(
            @Schema(description = "The value as printed on the receipt.",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String raw,
            @Schema(description = "The possible readings when the printed value is ambiguous (never guessed).")
            List<String> candidates,
            @Schema(description = "OK, AMBIGUOUS, INVALID, INCONSISTENT, NOT_CORROBORATED, STALE or MISSING.")
            String status,
            @Schema(description = "Model-reported, uncalibrated: display only.",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable BigDecimal confidence) {
    }

    @Schema(name = "ReceiptReviewLine", requiredProperties = {"position", "type", "description", "amountRaw",
            "amountStatus"})
    record Line(int position, String type, String description, String amountRaw,
            @Schema(implementation = MoneySchema.class, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable Money amount, String amountStatus) {
    }

    @Schema(name = "ReceiptSuggestedCategory", requiredProperties = {"categoryId", "source"})
    record SuggestedCategory(UUID categoryId,
            @Schema(description = "USER_RULE, HOUSEHOLD_RULE or DEFAULT_OTHER.") String source) {
    }

    @Schema(name = "ReceiptDuplicate", requiredProperties = {"reason", "attachable"})
    record Duplicate(ReceiptReviewView.DuplicateReason reason,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable UUID receiptId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable UUID expenseId,
            @Schema(description = "Whether this receipt can be attached to that expense instead.")
            boolean attachable) {
    }

    @Schema(name = "ReceiptReview", requiredProperties = {"receiptId", "version", "state", "analysed",
            "refundProposed", "householdCurrency", "fields", "unresolvedFields", "lines", "findings", "duplicates"})
    record ReviewResponse(UUID receiptId,
            @Schema(description = "Send it back with the confirmation.") long version,
            ReceiptState state,
            @Schema(description = "false: no draft, the member enters every value.") boolean analysed,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String documentType,
            boolean refundProposed, String householdCurrency,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String proposedMerchant,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable LocalDate proposedDate,
            @Schema(description = "Present only when the printed total has exactly one reading.",
                    implementation = MoneySchema.class, requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable Money proposedTotal,
            @Schema(description = "ISO 4217 code printed on the receipt, when recognised.",
                    requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable String detectedCurrency,
            @Schema(description = "merchant, date, total and currency.") Map<String, Field> fields,
            @Schema(description = "Fields to resolve individually before confirming (BR-RCP-10).")
            List<String> unresolvedFields,
            List<Line> lines,
            @Schema(description = "Consistency findings and warnings, advisory.") List<String> findings,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable SuggestedCategory suggestedCategory,
            @Schema(description = "Potential duplicates (BR-RCP-14): warnings, never a block.")
            List<Duplicate> duplicates) {

        static ReviewResponse from(ReceiptReviewView v) {
            Map<String, Field> fields = new java.util.LinkedHashMap<>();
            v.fields().forEach((name, f) -> fields.put(name, new Field(f.raw(), f.candidates(), f.status(),
                    f.confidence())));
            return new ReviewResponse(v.receiptId(), v.version(), v.state(), v.analysed(), v.documentType(),
                    v.refundProposed(), v.householdCurrency(), v.proposedMerchant(), v.proposedDate(),
                    v.proposedTotal(), v.detectedCurrency(), fields, v.unresolvedFields(),
                    v.lines().stream().map(l -> new Line(l.position(), l.type(), l.description(), l.amountRaw(),
                            l.amount(), l.amountStatus())).toList(),
                    v.findings(),
                    v.suggestedCategory() == null ? null : new SuggestedCategory(
                            v.suggestedCategory().categoryId(), v.suggestedCategory().source()),
                    v.duplicates().stream().map(d -> new Duplicate(d.reason(), d.receiptId(), d.expenseId(),
                            d.attachable())).toList());
        }
    }

    /** No household, creator or owner field: they come from the authenticated user. */
    @Schema(name = "ConfirmReceiptRequest")
    record ConfirmRequest(
            @Schema(description = "The receipt version the member reviewed.") @NotNull Long version,
            @Schema(description = "EXPENSE (default) or REFUND.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable Kind kind,
            @Schema(description = "Amount of the expense, in the household currency, strictly positive.",
                    implementation = MoneySchema.class) @NotNull Money amount,
            @Schema(description = "Date of the expense (YYYY-MM-DD).", example = "2026-10-04")
            @NotNull LocalDate date,
            @Schema(description = "A usable category of the household: one category for the whole amount.")
            @NotNull UUID categoryId,
            @Schema(description = "A current member of the household.") @NotNull UUID paidByUserId,
            @Schema(description = "SHARED (default) or PERSONAL.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable Sharing sharingType,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable @Size(max = MERCHANT_MAX)
            String merchant,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Nullable @Size(max = NOTE_MAX) String note,
            @Schema(description = "The total the member explicitly acknowledged: must equal amount (BR-RCP-10).",
                    implementation = MoneySchema.class) @NotNull Money acknowledgedTotal,
            @Schema(description = "The date the member explicitly acknowledged: must equal date (BR-RCP-10).")
            @NotNull LocalDate acknowledgedDate,
            @Schema(description = "Field (merchant, date, total, currency) → the value chosen, for every field "
                    + "listed in unresolvedFields.", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
            @Nullable @Size(max = 4)
            Map<String, @NotNull @Size(max = ReceiptConfirmationPolicy.RESOLUTION_MAX_LENGTH) String> resolutions) {

        ConfirmReceiptService.Confirmation toCommand() {
            return new ConfirmReceiptService.Confirmation(version, kind == Kind.REFUND, amount, date, categoryId,
                    paidByUserId, sharingType == Sharing.PERSONAL, merchant, note, acknowledgedTotal,
                    acknowledgedDate, resolutions == null ? Map.of() : resolutions);
        }
    }

    @Schema(name = "AttachReceiptRequest")
    record AttachRequest(
            @Schema(description = "A live expense of the household the caller can edit, without a receipt.")
            @NotNull UUID expenseId,
            @Schema(description = "The receipt version the member reviewed.") @NotNull Long version) {
    }

    @Schema(name = "ReceiptLink", requiredProperties = {"receiptId", "expenseId", "state"})
    record LinkResponse(UUID receiptId, UUID expenseId, ReceiptState state) {
        static LinkResponse from(ConfirmReceiptService.Linked linked) {
            return new LinkResponse(linked.receiptId(), linked.expenseId(), linked.state());
        }
    }

    @GetMapping("/review")
    @Operation(operationId = "getReceiptReview", summary = "Review an open receipt",
            description = "The draft of one of the caller's open receipts as proposals: printed values, candidate "
                    + "readings, statuses, a rule-based category suggestion (from the merchant name only, always a "
                    + "usable category of the household) and potential duplicates the caller may see (BR-RCP-14). "
                    + "Nothing here is an expense (BR-RCP-02). A receipt never analysed is reviewable too: the "
                    + "member then enters every value.")
    @ApiResponse(responseCode = "200", description = "The review")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or RECEIPT_NOT_FOUND (unknown, other "
            + "household, partner's draft, or no longer open)",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    ReviewResponse review(@PathVariable UUID id) {
        return ReviewResponse.from(review.review(id));
    }

    @PostMapping("/confirm")
    @Operation(operationId = "confirmReceipt", summary = "Confirm a receipt into a new expense",
            description = "Creates ONE expense from the values the member submitted and explicitly acknowledged, "
                    + "and links the receipt to it, exactly once (BR-RCP-10, BR-RCP-15). Every rule of a manual "
                    + "expense applies; if the expense is refused nothing changes and the receipt stays open. "
                    + "Allowed from any open receipt of the caller, analysed or not. Send an Idempotency-Key to "
                    + "retry safely.")
    @ApiResponse(responseCode = "201", description = "Expense created and linked")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, RECEIPT_ACKNOWLEDGEMENT_MISMATCH, "
            + "RECEIPT_FIELD_UNRESOLVED, RECEIPT_RESOLUTION_INVALID, IDEMPOTENCY_KEY_INVALID or any validation "
            + "error of a manual expense",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND, RECEIPT_NOT_FOUND or CATEGORY_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "RECEIPT_ALREADY_CONFIRMED, RECEIPT_VERSION_CONFLICT or "
            + "REQUEST_IN_PROGRESS",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<LinkResponse> confirm(@PathVariable UUID id,
            @Parameter(description = "Client-generated key (1-100 printable ASCII characters).")
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody ConfirmRequest request) {
        ConfirmReceiptService.Result result = confirm.confirm(id, request.toCommand(),
                IdempotencyKey.fromHeader(idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED).body(LinkResponse.from(result.linked()));
    }

    @PostMapping("/attach")
    @Operation(operationId = "attachReceipt", summary = "Attach a receipt to an existing expense",
            description = "Keeps the receipt as evidence of an expense that already exists, instead of creating a "
                    + "duplicate (BR-RCP-14). Nothing of the expense changes. The caller must be able to edit the "
                    + "expense: the partner's PERSONAL expense is not found.")
    @ApiResponse(responseCode = "200", description = "Receipt attached")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED or IDEMPOTENCY_KEY_INVALID",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND, RECEIPT_NOT_FOUND or EXPENSE_NOT_FOUND",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "RECEIPT_ALREADY_CONFIRMED, RECEIPT_VERSION_CONFLICT, "
            + "EXPENSE_RECEIPT_ALREADY_LINKED or REQUEST_IN_PROGRESS",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED",
            content = @Content(mediaType = PROBLEM, schema = @Schema(implementation = ProblemSchema.class)))
    LinkResponse attach(@PathVariable UUID id,
            @Parameter(description = "Client-generated key (1-100 printable ASCII characters).")
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody AttachRequest request) {
        return LinkResponse.from(confirm.attach(id, request.expenseId(), request.version(),
                IdempotencyKey.fromHeader(idempotencyKey)).linked());
    }
}
