package com.couplefinance.receipt.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.couplefinance.shared.error.ApplicationException;
import com.couplefinance.shared.id.HouseholdId;
import com.couplefinance.shared.id.UserId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ReceiptTest {

    private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");

    private static ReceiptPage page(int position) {
        return new ReceiptPage(UUID.randomUUID(), position, "receipts/" + UUID.randomUUID(), "image/jpeg", 1000, 10,
                20, new byte[32], 42L);
    }

    private static Receipt receipt(ReceiptStatus status, List<ReceiptPage> pages) {
        return new Receipt(UUID.randomUUID(), new HouseholdId(UUID.randomUUID()), new UserId(UUID.randomUUID()),
                SourceKind.IMAGES, 3000, status, AnalysisStatus.SKIPPED_NO_CONSENT, NOW, NOW, 0, pages);
    }

    @Test
    void BR_RCP_17_an_upload_is_an_open_draft_never_sent_to_ai() {
        Receipt receipt = Receipt.uploaded(UUID.randomUUID(), new HouseholdId(UUID.randomUUID()),
                new UserId(UUID.randomUUID()), SourceKind.PDF, 5000, List.of(page(2), page(1)), NOW);
        assertThat(receipt.status()).isEqualTo(ReceiptStatus.OPEN);
        assertThat(receipt.analysisStatus()).isEqualTo(AnalysisStatus.SKIPPED_NO_CONSENT);
        assertThat(receipt.isDraft()).isTrue();
        assertThat(receipt.pages()).extracting(ReceiptPage::position).containsExactly(1, 2);
        assertThat(receipt.page(2)).isPresent();
        assertThat(receipt.page(3)).isEmpty();
        assertThat(ReceiptState.of(receipt.status(), receipt.analysisStatus())).isEqualTo(ReceiptState.UPLOADED);
    }

    @Test
    void pages_are_one_to_five_and_contiguous() {
        assertThatThrownBy(() -> receipt(ReceiptStatus.OPEN, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receipt(ReceiptStatus.OPEN, List.of(page(1), page(3))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> page(6)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReceiptPage(UUID.randomUUID(), 1, "k", "image/jpeg", 10, 1, 1, new byte[31],
                null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void page_hashes_are_defensively_copied() {
        byte[] sha = new byte[32];
        ReceiptPage page = new ReceiptPage(UUID.randomUUID(), 1, "k", "image/jpeg", 10, 1, 1, sha, null);
        sha[0] = 1;
        page.originalSha256()[1] = 1;
        assertThat(page.originalSha256()).containsOnly(0);
    }

    @Test
    void BR_RCP_20_only_an_open_receipt_is_deletable_from_the_vault() {
        receipt(ReceiptStatus.OPEN, List.of(page(1))).requireDeletable();
        for (ReceiptStatus linked : List.of(ReceiptStatus.CONFIRMED, ReceiptStatus.ATTACHED, ReceiptStatus.ABANDONED)) {
            assertThatThrownBy(() -> receipt(linked, List.of(page(1))).requireDeletable())
                    .isInstanceOfSatisfying(ApplicationException.class, e -> assertThat(e.errorCode())
                            .isEqualTo(ReceiptErrorCode.RECEIPT_NOT_DELETABLE));
        }
    }

    @ParameterizedTest
    @EnumSource(AnalysisStatus.class)
    void the_api_state_maps_both_dimensions(AnalysisStatus analysis) {
        ReceiptState open = ReceiptState.of(ReceiptStatus.OPEN, analysis);
        assertThat(open).isEqualTo(switch (analysis) {
            case SKIPPED_NO_CONSENT, CANCELLED -> ReceiptState.UPLOADED;
            case PENDING -> ReceiptState.QUEUED;
            case RUNNING -> ReceiptState.ANALYSING;
            case SUCCEEDED -> ReceiptState.READY_FOR_REVIEW;
            case FAILED -> ReceiptState.FAILED;
        });
        assertThat(ReceiptState.of(ReceiptStatus.CONFIRMED, analysis)).isEqualTo(ReceiptState.CONFIRMED);
        assertThat(ReceiptState.of(ReceiptStatus.ATTACHED, analysis)).isEqualTo(ReceiptState.ATTACHED);
        assertThat(ReceiptState.of(ReceiptStatus.ABANDONED, analysis)).isEqualTo(ReceiptState.ABANDONED);
    }
}
