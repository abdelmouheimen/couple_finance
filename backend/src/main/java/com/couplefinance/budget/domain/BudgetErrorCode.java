package com.couplefinance.budget.domain;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable error codes of the budget module. Amount and currency errors reuse the Money codes. */
public enum BudgetErrorCode implements ErrorCode {

    /** BR-BUD-02: a budget has at least one limit. */
    BUDGET_LIMIT_REQUIRED(HttpStatus.BAD_REQUEST),
    /** BR-BUD-01: two lines for the same category in one budget. */
    DUPLICATE_CATEGORY_LIMIT(HttpStatus.BAD_REQUEST),
    /** More category limits than a budget may hold. */
    TOO_MANY_CATEGORY_LIMITS(HttpStatus.BAD_REQUEST),
    /** BR-CAT-03: a new category line references an archived category. */
    BUDGET_CATEGORY_ARCHIVED(HttpStatus.BAD_REQUEST),
    /** A referenced category is unknown or belongs to another household (404, non-disclosure). */
    CATEGORY_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** BR-HH-07: the path date is not the start of an existing period of the household calendar (404). */
    BUDGET_PERIOD_NOT_FOUND(HttpStatus.NOT_FOUND),
    /** The period exists but has no budget yet (404). */
    BUDGET_NOT_FOUND(HttpStatus.NOT_FOUND);

    private final HttpStatus status;

    BudgetErrorCode(HttpStatus status) {
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
