package com.couplefinance.expense.web;

import java.time.LocalDate;
import java.util.UUID;

import com.couplefinance.expense.application.CreateExpenseService;
import com.couplefinance.expense.application.ExpenseDeleter;
import com.couplefinance.expense.application.ExpenseQueryService;
import com.couplefinance.expense.application.ExpenseUpdater;
import com.couplefinance.expense.application.ListExpensesQuery;
import com.couplefinance.expense.application.ListExpensesService;
import com.couplefinance.expense.domain.ExpenseKind;
import com.couplefinance.expense.domain.ExpenseScope;
import com.couplefinance.shared.pagination.PageQuery;
import com.couplefinance.shared.concurrency.VersionETag;
import com.couplefinance.shared.error.ProblemSchema;
import com.couplefinance.shared.idempotency.IdempotencyKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Expenses of the caller's household (BR-EXP-01..14). */
@RestController
@RequestMapping("/api/v1/expenses")
@Tag(name = "Expenses")
class ExpenseController {

    private final CreateExpenseService createExpense;
    private final ListExpensesService listExpenses;

    private final ExpenseQueryService queries;
    private final ExpenseUpdater updater;
    private final ExpenseDeleter deleter;

    ExpenseController(CreateExpenseService createExpense, ListExpensesService listExpenses,
            ExpenseQueryService queries, ExpenseUpdater updater, ExpenseDeleter deleter) {
        this.deleter = deleter;
        this.updater = updater;
        this.createExpense = createExpense;
        this.listExpenses = listExpenses;
        this.queries = queries;
    }

    @GetMapping
    @Operation(operationId = "listExpenses", summary = "List and search expenses",
            description = "Expense history, newest first (date, then id), cursor-paginated. scope=HOUSEHOLD lists "
                    + "the SHARED expenses of the caller's household; scope=PERSONAL lists the caller's own "
                    + "PERSONAL expenses. A partner's PERSONAL expenses never appear, nor in totals or counts. "
                    + "Deleted expenses are excluded. Filters combine with AND. The totals cover every expense "
                    + "matching the filters, not only the page, and carry their scope. Readable on a dissolved "
                    + "household by a former member with archive access.")
    @ApiResponse(responseCode = "200", description = "A page of expenses")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED (missing or malformed parameter), "
            + "INVALID_CURSOR, INVALID_LIMIT or EXPENSE_FILTER_INVALID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND (the caller has no household)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ExpensePageResponse list(
            @Parameter(description = "Which view to list.", required = true) @RequestParam ExpenseScope scope,
            @Parameter(description = "First date included (YYYY-MM-DD).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @Parameter(description = "Last date included (YYYY-MM-DD).")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @Parameter(description = "Only expenses having an item of this category.")
            @RequestParam(required = false) UUID categoryId,
            @Parameter(description = "Only expenses paid by this member.")
            @RequestParam(required = false) UUID paidBy,
            @Parameter(description = "Only this kind.") @RequestParam(required = false) ExpenseKind kind,
            @Parameter(description = "Only expenses with (true) or without (false) a receipt.")
            @RequestParam(required = false) Boolean hasReceipt,
            @Parameter(description = "Case-insensitive text contained in the merchant or the note (max 100).")
            @RequestParam(required = false) String q,
            @Parameter(description = "Page size, default 20, capped at 100.")
            @RequestParam(required = false) Integer limit,
            @Parameter(description = "Opaque cursor of the previous page.")
            @RequestParam(required = false) String cursor) {
        return ExpensePageResponse.from(listExpenses.list(new ListExpensesQuery(scope, dateFrom, dateTo,
                categoryId, paidBy, kind, hasReceipt, q, PageQuery.of(limit, cursor))));
    }

    @GetMapping("/{id}")
    @Operation(operationId = "getExpense", summary = "Read an expense",
            description = "One expense with its items. The ETag header carries the version. A deleted expense, an "
                    + "expense of another household and a PERSONAL expense of the partner are all a 404 "
                    + "(BR-EXP-07). Readable by archive readers of a dissolved household.")
    @ApiResponse(responseCode = "200", description = "The expense")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or EXPENSE_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<ExpenseResponse> get(@PathVariable UUID id) {
        var expense = queries.get(id);
        return ResponseEntity.ok().eTag(VersionETag.render(expense.version()))
                .body(ExpenseResponse.from(expense));
    }

    @GetMapping("/{id}/audit")
    @Operation(operationId = "getExpenseAudit", summary = "Read the audit trail of an expense",
            description = "Who changed what and when (BR-EXP-10), oldest first. Available whenever the expense "
                    + "can be read; the audit of a PERSONAL expense is visible only to its owner, otherwise 404.")
    @ApiResponse(responseCode = "200", description = "The audit trail")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or EXPENSE_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ExpenseAuditResponse audit(@PathVariable UUID id) {
        return ExpenseAuditResponse.from(queries.audit(id));
    }

    @PutMapping("/{id}")
    @Operation(operationId = "updateExpense", summary = "Update an expense",
            description = "Replaces the editable state of an expense (amount, date, items, payer, sharing type, "
                    + "merchant, note) with the same validation as creation. Requires If-Match with the version "
                    + "(ETag) last read: missing is a 428, stale a 412. Any member edits a SHARED expense; only "
                    + "the owner a PERSONAL one (the partner gets a 404); only the payer can switch between "
                    + "SHARED and PERSONAL. A deleted expense is a 404. The edit is audited and increments the "
                    + "version; the new ETag is returned. Rejected on a dissolved household.")
    @ApiResponse(responseCode = "200", description = "Expense updated (or unchanged)")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST, IF_MATCH_INVALID, "
            + "INVALID_AMOUNT_FORMAT, TOO_MANY_DECIMALS, AMOUNT_NOT_POSITIVE, AMOUNT_EXCEEDS_MAXIMUM, "
            + "CURRENCY_MISMATCH, EXPENSE_DATE_OUT_OF_RANGE, EXPENSE_ITEM_COUNT_INVALID, "
            + "EXPENSE_ITEM_DUPLICATE_CATEGORY, EXPENSE_ITEMS_SUM_MISMATCH, EXPENSE_CATEGORY_ARCHIVED, "
            + "EXPENSE_PAID_BY_INVALID, EXPENSE_PERSONAL_PAYER_MISMATCH, EXPENSE_MERCHANT_INVALID, "
            + "EXPENSE_REFUND_ORIGINAL_INVALID, EXPENSE_REFUND_VISIBILITY_MISMATCH, "
            + "EXPENSE_REFUND_DATE_BEFORE_ORIGINAL or EXPENSE_REFUND_EXCEEDS_ORIGINAL",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED, HOUSEHOLD_READ_ONLY or "
            + "EXPENSE_SHARING_CHANGE_FORBIDDEN", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND, EXPENSE_NOT_FOUND (unknown, deleted, "
            + "of another household or personal to the partner) or CATEGORY_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "412", description = "VERSION_CONFLICT (stale If-Match)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "428", description = "IF_MATCH_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<ExpenseResponse> update(@PathVariable UUID id,
            @Parameter(description = "Version of the expense last read, as sent in its ETag.")
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @Valid @RequestBody UpdateExpenseRequest request) {
        var expense = updater.update(request.toCommand(id, ifMatch));
        return ResponseEntity.ok().eTag(VersionETag.render(expense.version()))
                .body(ExpenseResponse.from(expense));
    }

    @DeleteMapping("/{id}")
    @Operation(operationId = "deleteExpense", summary = "Delete an expense",
            description = "Logical deletion (BR-EXP-11): the expense leaves every list, total, budget and analytic "
                    + "and can be restored for 90 days. Any member deletes a SHARED expense; only the owner a "
                    + "PERSONAL one (the partner gets a 404). An expense with live refunds cannot be deleted "
                    + "(409 EXPENSE_HAS_LIVE_REFUNDS). The deletion is audited and emits ExpenseDeleted. No "
                    + "If-Match is required: deleting is idempotent in effect and a second delete is a 404. "
                    + "Rejected on a dissolved household.")
    @ApiResponse(responseCode = "204", description = "Expense deleted")
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or EXPENSE_NOT_FOUND (unknown, already "
            + "deleted, of another household or personal to the partner)",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "EXPENSE_HAS_LIVE_REFUNDS",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<Void> delete(@PathVariable UUID id) {
        deleter.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/restore")
    @Operation(operationId = "restoreExpense", summary = "Restore a deleted expense",
            description = "Restores a deleted expense within 90 days of its deletion (BR-EXP-11), under the same "
                    + "edit rights as deletion. After 90 days, or when the expense is not deleted, is unknown, "
                    + "belongs to another household or is personal to the partner, the answer is a 404. "
                    + "Restoring a refund re-checks BR-EXP-03 against its original (400 "
                    + "EXPENSE_REFUND_ORIGINAL_INVALID when the original is deleted, EXPENSE_REFUND_EXCEEDS_ORIGINAL, "
                    + "EXPENSE_REFUND_DATE_BEFORE_ORIGINAL, EXPENSE_REFUND_VISIBILITY_MISMATCH). Audited; emits "
                    + "ExpenseRestored. The restored expense is returned with its new ETag. Rejected on a "
                    + "dissolved household.")
    @ApiResponse(responseCode = "200", description = "Expense restored")
    @ApiResponse(responseCode = "400", description = "EXPENSE_REFUND_ORIGINAL_INVALID, "
            + "EXPENSE_REFUND_EXCEEDS_ORIGINAL, EXPENSE_REFUND_DATE_BEFORE_ORIGINAL or "
            + "EXPENSE_REFUND_VISIBILITY_MISMATCH",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND or EXPENSE_NOT_FOUND",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<ExpenseResponse> restore(@PathVariable UUID id) {
        var expense = deleter.restore(id);
        return ResponseEntity.ok().eTag(VersionETag.render(expense.version()))
                .body(ExpenseResponse.from(expense));
    }

    @PostMapping
    @Operation(operationId = "createExpense", summary = "Create an expense or a refund",
            description = "Creates an EXPENSE or a REFUND (optionally linked to an original expense) in the "
                    + "caller's household. The household comes from the "
                    + "authenticated user. The ETag header carries the version. Send an Idempotency-Key to retry "
                    + "safely: the same key and request return the original response (24 h); the same key with a "
                    + "different request is a 422; the same key while the first request is running is a 409.")
    @ApiResponse(responseCode = "201", description = "Expense created")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED, MALFORMED_REQUEST, INVALID_AMOUNT_FORMAT, "
            + "TOO_MANY_DECIMALS, AMOUNT_NOT_POSITIVE, AMOUNT_EXCEEDS_MAXIMUM, CURRENCY_MISMATCH, "
            + "EXPENSE_DATE_OUT_OF_RANGE, EXPENSE_ITEM_COUNT_INVALID, EXPENSE_ITEM_DUPLICATE_CATEGORY, "
            + "EXPENSE_ITEMS_SUM_MISMATCH, EXPENSE_CATEGORY_ARCHIVED, EXPENSE_PAID_BY_INVALID, "
            + "EXPENSE_PERSONAL_PAYER_MISMATCH, EXPENSE_MERCHANT_INVALID, EXPENSE_REFUND_OF_NOT_ALLOWED, "
            + "EXPENSE_REFUND_ORIGINAL_INVALID, EXPENSE_REFUND_VISIBILITY_MISMATCH, "
            + "EXPENSE_REFUND_DATE_BEFORE_ORIGINAL, EXPENSE_REFUND_EXCEEDS_ORIGINAL or IDEMPOTENCY_KEY_INVALID",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "401", description = "AUTHENTICATION_REQUIRED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "403", description = "EMAIL_NOT_VERIFIED or HOUSEHOLD_READ_ONLY",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "404", description = "HOUSEHOLD_NOT_FOUND, EXPENSE_NOT_FOUND (refundOf unknown, deleted, of another "
            + "household or "
            + "personal to the partner) or CATEGORY_NOT_FOUND (also for a "
            + "category of another household)", content = @Content(mediaType = "application/problem+json",
            schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "409", description = "REQUEST_IN_PROGRESS",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    @ApiResponse(responseCode = "422", description = "IDEMPOTENCY_KEY_REUSED",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemSchema.class)))
    ResponseEntity<ExpenseResponse> create(
            @Parameter(description = "Client-generated key (1-100 printable ASCII characters) making the "
                    + "creation safe to retry.")
            @RequestHeader(value = IdempotencyKey.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateExpenseRequest request) {
        CreateExpenseService.Created created = createExpense.create(request.toCommand(),
                IdempotencyKey.fromHeader(idempotencyKey));
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(VersionETag.render(created.expense().version()))
                .body(ExpenseResponse.from(created.expense()));
    }
}
