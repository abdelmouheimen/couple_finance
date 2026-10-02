package com.couplefinance.expense.domain;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable error codes of the expense module. Currency, amount and format errors reuse the Money codes. */
public enum ExpenseErrorCode implements ErrorCode {

    /** BR-EXP-06: the date is more than 1 day after today or more than 5 years in the past. */
    EXPENSE_DATE_OUT_OF_RANGE(HttpStatus.BAD_REQUEST),
    /** BR-EXP-08: an expense has 1 to 10 items. */
    EXPENSE_ITEM_COUNT_INVALID(HttpStatus.BAD_REQUEST),
    /** BR-EXP-08: at most one item per category. */
    EXPENSE_ITEM_DUPLICATE_CATEGORY(HttpStatus.BAD_REQUEST),
    /** BR-EXP-08: the items must sum to the expense amount exactly. */
    EXPENSE_ITEMS_SUM_MISMATCH(HttpStatus.BAD_REQUEST),
    /** BR-EXP-14: an item references an archived category. */
    EXPENSE_CATEGORY_ARCHIVED(HttpStatus.BAD_REQUEST),
    /** BR-EXP-14: an item references a category that does not exist for the household (404, non-disclosure). */
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** BR-EXP-07: paid-by is not a current member of the household. */
    EXPENSE_PAID_BY_INVALID(HttpStatus.BAD_REQUEST),
    /** BR-EXP-07: a PERSONAL expense is paid by its creator. */
    EXPENSE_PERSONAL_PAYER_MISMATCH(HttpStatus.BAD_REQUEST),
    /** The merchant carries no information once normalised (only punctuation or symbols). */
    EXPENSE_MERCHANT_INVALID(HttpStatus.BAD_REQUEST),
    /** BR-EXP-03: {@code refundOf} is only allowed on a REFUND. */
    EXPENSE_REFUND_OF_NOT_ALLOWED(HttpStatus.BAD_REQUEST),
    /** BR-EXP-03: the original of a refund is an EXPENSE (not a refund, not a transfer). */
    EXPENSE_REFUND_ORIGINAL_INVALID(HttpStatus.BAD_REQUEST),
    /** BR-EXP-03: a refund has the same sharing type as its original. */
    EXPENSE_REFUND_VISIBILITY_MISMATCH(HttpStatus.BAD_REQUEST),
    /** BR-EXP-03: a refund is dated on or after its original. */
    EXPENSE_REFUND_DATE_BEFORE_ORIGINAL(HttpStatus.BAD_REQUEST),
    /** BR-EXP-03: the live refunds of an expense sum to at most its amount. */
    EXPENSE_REFUND_EXCEEDS_ORIGINAL(HttpStatus.BAD_REQUEST),
    /** The original of a refund does not exist, is deleted, or is not visible to the caller (404). */
    EXPENSE_NOT_FOUND(HttpStatus.NOT_FOUND);

    private final HttpStatus status;

    ExpenseErrorCode(HttpStatus status) {
        this.status = status;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
