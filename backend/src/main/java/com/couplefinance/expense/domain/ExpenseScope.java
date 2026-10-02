package com.couplefinance.expense.domain;

/** BR-SCP-03: the scope a listing or a total is labelled with. */
public enum ExpenseScope {
    /** SHARED expenses of the household (BR-SCP-01). */
    HOUSEHOLD(SharingType.SHARED),
    /** The caller's own PERSONAL expenses (BR-SCP-02). */
    PERSONAL(SharingType.PERSONAL);

    private final SharingType sharingType;

    ExpenseScope(SharingType sharingType) {
        this.sharingType = sharingType;
    }

    public SharingType sharingType() {
        return sharingType;
    }
}
