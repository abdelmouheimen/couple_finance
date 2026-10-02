package com.couplefinance.budget.domain;

import com.couplefinance.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;

/** Stable error codes of the budget module. Amount and currency errors reuse the Money codes. */
public enum BudgetErrorCode implements ErrorCode {

    /** BR-BUD-02: a budget has at least one limit. */
    BUDGET_LIMIT_REQUIRED(HttpStatus.BAD_REQUEST),
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
