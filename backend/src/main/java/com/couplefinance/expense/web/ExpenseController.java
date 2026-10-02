package com.couplefinance.expense.web;

import java.time.LocalDate;
import java.util.UUID;

import com.couplefinance.expense.application.CreateExpenseService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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

    ExpenseController(CreateExpenseService createExpense, ListExpensesService listExpenses) {
        this.createExpense = createExpense;
        this.listExpenses = listExpenses;
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
