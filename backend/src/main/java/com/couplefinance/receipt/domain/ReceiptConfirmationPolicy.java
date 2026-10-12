package com.couplefinance.receipt.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.money.Money;

/**
 * Guards of the confirmation of a receipt (domain-model.md section 6.6). Pure: the caller has loaded the receipt
 * under a row lock. Nothing here trusts the draft as money: the amount and date that become an expense are the
 * values the member submitted and explicitly acknowledged (BR-RCP-10); the draft only says which fields the member
 * had to resolve.
 */
public final class ReceiptConfirmationPolicy {

    /** The draft fields a member reviews; each one whose status is not OK must be resolved individually. */
    public static final List<String> REVIEW_FIELDS = List.of("merchant", "date", "total", "currency");

    public static final int RESOLUTION_MAX_LENGTH = 200;

    private static final Set<String> KNOWN = Set.copyOf(REVIEW_FIELDS);

    private ReceiptConfirmationPolicy() {
    }

    /** BR-RCP-15: only an open receipt, in the version the member reviewed, can be linked — exactly once. */
    public static void requireLinkable(Receipt receipt, long reviewedVersion) {
        if (receipt.status() != ReceiptStatus.OPEN) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_ALREADY_CONFIRMED,
                    "This receipt is already linked to an expense.");
        }
        if (receipt.version() != reviewedVersion) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_VERSION_CONFLICT,
                    "This receipt changed since it was displayed. Review it again.");
        }
    }

    /**
     * BR-RCP-10: the acknowledged total and date are exactly the submitted expense values, and every draft field
     * whose status is not OK carries a resolution.
     *
     * @param draft       the review draft, absent when the receipt was never analysed (manual entry)
     * @param resolutions field name → the value the member chose or typed
     */
    public static void requireReviewed(Optional<ReceiptDraft> draft, Money amount, LocalDate date,
            Money acknowledgedTotal, LocalDate acknowledgedDate, Map<String, String> resolutions) {
        if (!amount.currency().equals(acknowledgedTotal.currency())
                || amount.minorUnits() != acknowledgedTotal.minorUnits() || !date.equals(acknowledgedDate)) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_ACKNOWLEDGEMENT_MISMATCH,
                    "The acknowledged total and date must be those of the expense.");
        }
        for (Map.Entry<String, String> resolution : resolutions.entrySet()) {
            if (!KNOWN.contains(resolution.getKey()) || resolution.getValue() == null
                    || resolution.getValue().length() > RESOLUTION_MAX_LENGTH) {
                throw new ApplicationException(ReceiptErrorCode.RECEIPT_RESOLUTION_INVALID,
                        "Resolutions are values of the fields merchant, date, total or currency.");
            }
        }
        List<String> unresolved = unresolved(draft, resolutions);
        if (!unresolved.isEmpty()) {
            throw new ApplicationException(ReceiptErrorCode.RECEIPT_FIELD_UNRESOLVED,
                    "Resolve these fields before confirming: " + String.join(", ", unresolved) + ".");
        }
    }

    /** The reviewed fields whose status is not OK and that have no resolution yet, in display order. */
    public static List<String> unresolved(Optional<ReceiptDraft> draft, Map<String, String> resolutions) {
        if (draft.isEmpty()) {
            return List.of();
        }
        return REVIEW_FIELDS.stream()
                .filter(field -> {
                    ReceiptDraft.Field value = draft.get().fields().get(field);
                    return value != null && !ReceiptFieldStatus.OK.name().equals(value.status())
                            && !resolutions.containsKey(field);
                })
                .toList();
    }
}
